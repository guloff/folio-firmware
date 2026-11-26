package com.rustamguloff.foliolink.core.services

import android.util.Log
import com.rustamguloff.foliolink.core.model.VocabWord
import com.rustamguloff.foliolink.core.store.SharedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Состояние SM-2 для одного слова словаря (хранится только на телефоне). Время — epoch-мс. */
@Serializable
data class ReviewState(
    val easiness: Double = 2.5,
    /** Дни. */
    val interval: Int = 0,
    val repetitions: Int = 0,
    /** Когда слово снова к повторению; 0 — уже сейчас (новое слово). */
    val due: Long = 0,
    val lastReviewed: Long? = null,
) {
    enum class Grade(val value: Int) { AGAIN(1), HARD(3), GOOD(4), EASY(5) }

    /** Классический SM-2. Оценка < 3 сбрасывает число повторений; новое состояние — копия. */
    fun reviewed(grade: Grade, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ReviewState {
        val q = grade.value.toDouble()
        var reps = repetitions
        var iv: Int
        if (grade.value < 3) {
            reps = 0
            iv = 0 // ещё раз сегодня
        } else {
            reps += 1
            iv = when (reps) {
                1 -> 1
                2 -> if (grade == Grade.EASY) 4 else 3
                else -> Math.round(maxOf(interval, 1) * easiness).toInt()
            }
            if (grade == Grade.HARD) iv = maxOf(1, (iv * 0.8).toInt())
        }
        val ef = maxOf(1.3, easiness + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
        val nextDue = if (iv == 0) {
            nowMs + 10 * 60 * 1000L
        } else {
            Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(iv.toLong())
                .atStartOfDay(zone).toInstant().toEpochMilli()
        }
        return ReviewState(easiness = ef, interval = iv, repetitions = reps, due = nextDue, lastReviewed = nowMs)
    }

    fun isDue(nowMs: Long = System.currentTimeMillis()): Boolean = due <= nowMs
}

/**
 * Состояния повторения по словам (ключ — слово в нижнем регистре), JSON-файл по пути `file`.
 * Чтение и запись — на Dispatchers.IO; до `load()` (или первого изменения) состояние пустое.
 *
 * Этих данных нет на читалке: нечитаемый файл откладывается в сторону, а не затирается первой
 * оценкой; [storageFailed] говорит интерфейсу, что прогресс повторения сейчас не сохраняется.
 */
class VocabReviewStore(private val file: File) {
    private val mutex = Mutex()
    private var loaded = false
    private val _states = MutableStateFlow<Map<String, ReviewState>>(emptyMap())
    val states: StateFlow<Map<String, ReviewState>> = _states.asStateFlow()

    private val _storageFailed = MutableStateFlow(false)
    /** Файл не прочитался или последняя запись не удалась. */
    val storageFailed: StateFlow<Boolean> = _storageFailed.asStateFlow()
    /** Нечитаемый файл не удалось отложить: поверх него не пишем. */
    private var writeBlocked = false

    suspend fun load() {
        mutex.withLock { ensureLoaded() }
    }

    fun state(word: String): ReviewState = _states.value[word.lowercase()] ?: ReviewState()

    suspend fun review(
        word: String,
        grade: ReviewState.Grade,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReviewState = mutex.withLock {
        ensureLoaded()
        val key = word.lowercase()
        val next = (_states.value[key] ?: ReviewState()).reviewed(grade, nowMs, zone)
        _states.value = _states.value + (key to next)
        save()
        next
    }

    suspend fun remove(word: String) {
        mutex.withLock {
            ensureLoaded()
            _states.value = _states.value - word.lowercase()
            save()
        }
    }

    /** Слова к повторению сейчас: самые «просроченные» первыми. */
    fun dueWords(words: List<VocabWord>, nowMs: Long = System.currentTimeMillis()): List<VocabWord> =
        words.filter { state(it.word).isDue(nowMs) }.sortedBy { state(it.word).due }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val decoded = withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            try {
                json.decodeFromString(serializer, file.readText())
            } catch (e: Exception) {
                val aside = SharedStore.setAside(file)
                writeBlocked = aside == null
                _storageFailed.value = true
                // Только класс исключения: в сообщении разбора бывает содержимое файла.
                Log.w(TAG, "vocab review file unreadable: ${e.javaClass.simpleName}" + (aside?.let { "; kept as ${it.name}" } ?: ""))
                null
            }
        }
        // Изменения, сделанные до загрузки, важнее прочитанного с диска.
        _states.value = (decoded ?: emptyMap()) + _states.value
        loaded = true
    }

    /** True — состояние записано. */
    private suspend fun save(): Boolean {
        val snapshot = _states.value
        val ok = withContext(Dispatchers.IO) {
            if (writeBlocked) return@withContext false
            try {
                SharedStore.writeAtomically(file, json.encodeToString(serializer, snapshot).toByteArray())
                true
            } catch (e: Exception) {
                Log.w(TAG, "vocab review write failed: ${e.javaClass.simpleName}")
                false
            }
        }
        _storageFailed.value = !ok
        return ok
    }

    private companion object {
        const val TAG = "VocabReview"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val serializer = MapSerializer(String.serializer(), ReviewState.serializer())
    }
}
