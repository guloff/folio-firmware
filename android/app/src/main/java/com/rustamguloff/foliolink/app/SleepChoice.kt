package com.rustamguloff.foliolink.app

import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.core.model.SleepResponse
import com.rustamguloff.foliolink.core.store.SettingsProfile
import kotlinx.serialization.json.JsonPrimitive

// «Заставка»: какую картинку с читалки показывает режим «Свой» (Folio 1.0:
// GET /api/inklink/sleep → `selected`, POST /api/inklink/sleep/select).

/** Картинка экрана сна в режимах с картинками. */
sealed interface SleepChoice {
    /** Каждый раз случайная из папки заставок. */
    data object Random : SleepChoice

    /** Всегда этот файл из папки заставок. */
    data class Image(val fileName: String) : SleepChoice

    /** Имя для /sleep/select ("" — случайная). */
    val name: String get() = (this as? Image)?.fileName.orEmpty()

    /** Картинка на читалке (null у случайной или у картинки, ещё ждущей в очереди). */
    fun file(sleep: SleepResponse?): RemoteFile? =
        (this as? Image)?.let { img -> sleep?.images?.firstOrNull { it.name == img.fileName } }

    /** Изменения в очереди, после которых читалка покажет этот выбор. */
    fun plan(mode: Int?, selectionSupported: Boolean = true): List<OutboxOperation> = when (this) {
        Random -> if (selectionSupported) listOf(OutboxOperation.SelectSleepImage("")) else emptyList()
        is Image -> SleepRules.showPlan(fileName, mode, selectionSupported)
    }

    companion object {
        fun of(name: String): SleepChoice = if (name.isEmpty()) Random else Image(name)

        /** Текущий выбор, как его знает приложение (с правками из очереди). */
        fun current(sleep: SleepResponse?): SleepChoice = of(sleep?.selected.orEmpty())
    }
}

object SleepRules {
    /** Режимы, которые показывают картинки из папки: «Свой», «Обложка + Свой», «Чередовать». */
    val CUSTOM_MODES = setOf(2, 4, 10)
    const val CUSTOM_MODE = 2

    fun modeShowsImages(mode: Int?): Boolean = mode != null && mode in CUSTOM_MODES

    /**
     * Изменения, чтобы читалка показывала `name` на каждом сне: выбор (если прошивка его знает)
     * и, когда текущий режим картинки не показывает (или неизвестен), переключение на «Свой».
     */
    fun showPlan(name: String, mode: Int?, selectionSupported: Boolean): List<OutboxOperation> = buildList {
        if (selectionSupported) add(OutboxOperation.SelectSleepImage(name))
        if (!modeShowsImages(mode)) add(OutboxOperation.Settings(mapOf("sleepScreen" to JsonPrimitive(CUSTOM_MODE))))
    }

    /** Изменение из очереди так, как его отразит читалка: офлайн-правки видны сразу. */
    fun apply(operation: OutboxOperation, sleep: SleepResponse?): SleepResponse? = when (operation) {
        is OutboxOperation.SelectSleepImage -> (sleep ?: SleepResponse()).copy(selected = operation.name)
        is OutboxOperation.Settings -> {
            val mode = operation.values["sleepScreen"]?.let { SettingsProfile.intValue(it) }
            if (mode == null) sleep else (sleep ?: SleepResponse()).copy(mode = mode)
        }
        is OutboxOperation.DeleteFile -> sleep?.let { s ->
            val clears = s.selected.isNotEmpty() && s.images.any { it.fullPath == operation.path && it.name == s.selected }
            s.copy(selected = if (clears) "" else s.selected, images = s.images.filter { it.fullPath != operation.path })
        }
        else -> sleep
    }
}
