package com.rustamguloff.foliolink.ui.firmware

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.security.FirmwareImageInfo
import com.rustamguloff.foliolink.core.security.FirmwareRisk
import com.rustamguloff.foliolink.core.security.FirmwareSignature
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.RequiresConnection
import com.rustamguloff.foliolink.ui.settings.ActionRow
import com.rustamguloff.foliolink.ui.settings.InfoRow
import com.rustamguloff.foliolink.ui.settings.NoteRow
import com.rustamguloff.foliolink.ui.settings.SettingsGroup
import com.rustamguloff.foliolink.ui.settings.readPickedFiles
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import com.rustamguloff.foliolink.ui.theme.FolioSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Итог проверки выбранного образа: null-проблема — подпись Folio сходится. */
private class Verification(val problem: String?, val info: FirmwareImageInfo?)

/**
 * «Прошивка» (iOS FirmwareView): текущая версия, последний релиз с GitHub (образ для этой платы
 * и подпись), либо свои .bin и .bin.sig. Подпись Folio проверяется ДО загрузки; неподписанное
 * не отправляется. Установку подтверждают на экране читалки.
 */
@Composable
fun FirmwareScreen() {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val transfer by model.transfer.collectAsStateWithLifecycle()
    val info = snapshot.info

    var selection by remember { mutableStateOf(FirmwareTexts.Selection(null, null, null)) }
    var verification by remember { mutableStateOf<Verification?>(null) }
    var release by remember { mutableStateOf<GitHubRelease?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    // Установка и её итог — в модели (BUG-039): поворот экрана не обрывает загрузку .bin/.sig.
    val result by model.firmwareResult.collectAsStateWithLifecycle()
    val installing by model.firmwareInstalling.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    /** SEC-026: откат, другая плата или образ без версии — второе, отдельное подтверждение. */
    var risks by remember { mutableStateOf<List<FirmwareRisk.Risk>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }

    // Хеш ~6 МБ образа — один раз на пару образ/подпись, не на каждый кадр прогресса.
    suspend fun verify(sel: FirmwareTexts.Selection) {
        val image = sel.image
        if (image == null) { verification = null; return }
        verification = withContext(Dispatchers.Default) {
            val problem = try {
                FirmwareSignature.verify(image, sel.signature)
                null
            } catch (e: Exception) {
                errorText(res, model, e)
            }
            Verification(problem, FirmwareImageInfo.parse(image))
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val (files, unreadable) = readPickedFiles(context, uris)
            if (unreadable.isNotEmpty()) {
                model.show(res.getString(R.string.firmware_unreadable, unreadable.joinToString(", ")), AppModel.Banner.Kind.ERROR)
            }
            val next = FirmwareTexts.select(selection, files.map { it.name to it.data })
            if (next.image !== selection.image) release = null
            selection = next
            model.clearFirmwareResult()
            verify(next)
        }
    }

    suspend fun fetchLatest() {
        working = res.getString(R.string.firmware_looking)
        fetching = true
        try {
            val rel = GitHubRelease.latest(model.settings.firmwareRepo, info?.board.orEmpty())
            release = rel
            working = res.getString(R.string.firmware_downloading, rel.assetName)
            val (status, data) = GitHubRelease.fetch(rel.downloadUrl, progress = model.beginTransfer(rel.assetName))
            GitHubRelease.validateDownload(status, data)
            model.endTransfer()
            working = res.getString(R.string.firmware_downloading_sig)
            val (sigStatus, sig) = GitHubRelease.fetch(rel.signatureUrl)
            if (sigStatus != 200) {
                throw if (sigStatus == 404) GitHubRelease.ReleaseError.NoSignature else GitHubRelease.ReleaseError.Http(sigStatus)
            }
            val next = FirmwareTexts.Selection(rel.assetName, data, sig)
            selection = next
            model.clearFirmwareResult()
            verify(next)
            verification?.problem?.let { model.show(it, AppModel.Banner.Kind.ERROR) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            model.show(errorText(res, model, e), AppModel.Banner.Kind.ERROR)
        } finally {
            working = null
            fetching = false
            model.endTransfer()
        }
    }

    fun install() {
        val image = selection.image ?: return
        val signature = selection.signature ?: return
        // Подпись ещё раз проверяет модель перед отправкой.
        model.startFirmwareInstall(image, signature, selection.imageName ?: "firmware.bin")
    }

    /** После первого подтверждения: есть риски — второе окно, нет — установка. */
    fun confirmed() {
        val image = selection.image ?: return
        val found = model.firmwareRisks(selection.imageName, image)
        if (found.isEmpty()) install() else risks = found
    }

    Scaffold(topBar = { FolioTopBar(stringResource(R.string.firmware_title)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            RequiresConnection {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SettingsGroup(title = stringResource(R.string.firmware_current_header)) {
                        InfoRow(stringResource(R.string.firmware_version), info?.firmware?.ifEmpty { null } ?: "—")
                        InfoRow(stringResource(R.string.firmware_board), info?.board?.let { Fmt.boardName(it) }?.ifEmpty { null } ?: "—")
                    }

                    SettingsGroup(
                        title = stringResource(R.string.firmware_image_header),
                        footer = stringResource(R.string.firmware_image_footer),
                    ) {
                        ActionRow(
                            stringResource(R.string.firmware_download_latest),
                            onClick = { scope.launch { fetchLatest() } },
                            icon = Icons.Outlined.CloudDownload,
                            enabled = working == null,
                            busy = fetching,
                        )
                        ActionRow(
                            stringResource(R.string.firmware_choose_files),
                            onClick = { picker.launch(arrayOf("*/*")) },
                            icon = Icons.Outlined.FolderOpen,
                            enabled = working == null,
                        )
                        release?.let { NoteRow(stringResource(R.string.firmware_release, it.tag, it.assetName)) }
                        val image = selection.image
                        if (image != null) {
                            InfoRow(selection.imageName ?: "firmware.bin", Fmt.bytes(context, image.size.toLong()))
                            verification?.info?.let { d ->
                                InfoRow(stringResource(R.string.firmware_in_image), "${d.project} ${d.version}".trim())
                            }
                            val v = verification
                            when {
                                v == null -> {}
                                v.problem != null -> NoteRow(v.problem, Icons.Outlined.GppBad, FolioDanger)
                                else -> NoteRow(stringResource(R.string.firmware_sig_ok), Icons.Outlined.Verified, FolioSuccess)
                            }
                        }
                    }

                    SettingsGroup(footer = stringResource(R.string.firmware_install_footer)) {
                        ActionRow(
                            stringResource(R.string.firmware_install),
                            onClick = { confirm = true },
                            icon = Icons.Outlined.SystemUpdateAlt,
                            enabled = selection.image != null && verification?.problem == null && verification != null &&
                                working == null && !installing,
                            busy = installing && transfer == null,
                        )
                        working?.let { NoteRow(it) }
                        if (installing && working == null) NoteRow(stringResource(R.string.firmware_uploading))
                        result?.let { r ->
                            NoteRow(
                                r.message,
                                if (r.ok) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                                if (r.ok) FolioSuccess else FolioDanger,
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirm) {
        val lines = buildList {
            add(stringResource(R.string.firmware_confirm_current, info?.firmware?.ifEmpty { null } ?: "—"))
            verification?.info?.let { add(stringResource(R.string.firmware_confirm_new, it.project, it.version)) }
            if (FirmwareTexts.lowBattery(info)) add(stringResource(R.string.firmware_confirm_battery, info?.battery ?: 0))
        }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.firmware_confirm_title, selection.imageName.orEmpty())) },
            text = { Text(lines.joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = { confirm = false; confirmed() }) {
                    Text(stringResource(R.string.firmware_send))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (risks.isNotEmpty()) {
        val lines = risks.map { r ->
            when (r) {
                is FirmwareRisk.Risk.Older -> stringResource(R.string.firmware_risk_older, r.image, r.current)
                is FirmwareRisk.Risk.OtherBoard -> stringResource(R.string.firmware_risk_board, r.image, Fmt.boardName(r.reader))
                is FirmwareRisk.Risk.UnknownVersion -> stringResource(R.string.firmware_risk_unknown, r.current)
            }
        }
        AlertDialog(
            onDismissRequest = { risks = emptyList() },
            icon = { androidx.compose.material3.Icon(Icons.Outlined.GppBad, contentDescription = null, tint = FolioDanger) },
            title = { Text(stringResource(R.string.firmware_risk_title)) },
            text = { Text(lines.joinToString("\n\n")) },
            confirmButton = {
                TextButton(onClick = { risks = emptyList(); install() }) {
                    Text(stringResource(R.string.firmware_install_anyway), color = FolioDanger)
                }
            },
            dismissButton = { TextButton(onClick = { risks = emptyList() }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private fun errorText(resources: Resources, model: AppModel, e: Throwable): String =
    FirmwareTexts.errorRes(e)?.let { (id, arg) ->
        if (arg != null) resources.getString(id, arg) else resources.getString(id)
    } ?: model.errorText(e)
