package com.rustamguloff.foliolink.ui.notes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.FolioApp
import com.rustamguloff.foliolink.core.services.ReviewState
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import com.rustamguloff.foliolink.ui.theme.FolioSuccess
import kotlinx.coroutines.launch

private val FolioWarning = Color(0xFFE08A1E)

/**
 * Карточки (iOS `FlashcardsView`): слово → касание открывает контекст → оценка «Снова/Трудно/
 * Хорошо/Легко» двигает слово по SM-2 ([com.rustamguloff.foliolink.core.services.VocabReviewStore]).
 * `words` — слова сеанса (снимок на момент начала).
 */
@Composable
internal fun FlashcardsScreen(words: List<String>, onClose: () -> Unit) {
    val model = LocalAppModel.current
    val review = (LocalContext.current.applicationContext as FolioApp).vocabReview
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    var index by rememberSaveable { mutableIntStateOf(0) }
    var flipped by rememberSaveable { mutableStateOf(false) }
    var reviewed by rememberSaveable { mutableIntStateOf(0) }

    fun grade(word: String, g: ReviewState.Grade) {
        // В области модели: оценка сохранится, даже если экран сразу закроют.
        model.scope.launch { review.review(word, g) }
        reviewed++
        flipped = false
        index++
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { FolioTopBar(stringResource(R.string.notes_flashcards), onBack = onClose) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (index < words.size) {
                val word = words[index]
                val entry = snapshot.vocab.firstOrNull { it.word == word }
                LinearProgressIndicator(progress = { index.toFloat() / words.size }, Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.notes_card_progress, index + 1, words.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    onClick = { flipped = !flipped },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 4.dp,
                ) {
                    AnimatedContent(flipped, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "card") { open ->
                        Column(
                            Modifier.fillMaxWidth().padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                word,
                                style = MaterialTheme.typography.displaySmall,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                            if (open) {
                                HorizontalDivider()
                                Text(
                                    highlighted(entry?.context.orEmpty(), word, MaterialTheme.colorScheme.primary),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Serif,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                                entry?.let { model.book(it.book) }?.let { book ->
                                    Text(
                                        book.displayTitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                LookUpButton(word)
                            } else {
                                Text(
                                    stringResource(R.string.notes_card_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                if (flipped) {
                    // Две строки по две кнопки: подписи не обрезаются и при крупном шрифте.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradeButton(stringResource(R.string.notes_grade_again), FolioDanger, Modifier.weight(1f)) {
                            grade(word, ReviewState.Grade.AGAIN)
                        }
                        GradeButton(stringResource(R.string.notes_grade_hard), FolioWarning, Modifier.weight(1f)) {
                            grade(word, ReviewState.Grade.HARD)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradeButton(stringResource(R.string.notes_grade_good), FolioSuccess, Modifier.weight(1f)) {
                            grade(word, ReviewState.Grade.GOOD)
                        }
                        GradeButton(stringResource(R.string.notes_grade_easy), MaterialTheme.colorScheme.primary, Modifier.weight(1f)) {
                            grade(word, ReviewState.Grade.EASY)
                        }
                    }
                } else {
                    Button(onClick = { flipped = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.notes_show_answer))
                    }
                }
            } else {
                Spacer(Modifier.size(48.dp))
                Icon(Icons.Default.Verified, contentDescription = null, tint = FolioSuccess, modifier = Modifier.size(64.dp))
                Text(
                    stringResource(R.string.notes_review_done, reviewed),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Button(onClick = onClose) { Text(stringResource(R.string.common_close)) }
            }
        }
    }
}

@Composable
private fun GradeButton(title: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) { Text(title, textAlign = TextAlign.Center) }
}

@Composable
private fun LookUpButton(word: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = { Sharing.lookUp(context, word) }) {
        Icon(Icons.Outlined.Translate, contentDescription = null)
        Text(stringResource(R.string.notes_lookup), Modifier.padding(start = 8.dp))
    }
}

/** Контекст со словом, выделенным цветом и жирным (как `highlighted` в iOS). */
private fun highlighted(context: String, word: String, accent: Color): AnnotatedString {
    val text = context.ifEmpty { "—" }
    val range = NotesLogic.wordRange(text, word) ?: return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold), range.first, range.last + 1)
    }
}
