package com.rustamguloff.foliolink.core.ble

import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.SyncRules
import com.rustamguloff.foliolink.core.model.SyncSnapshot
import com.rustamguloff.foliolink.core.net.ConnectionPolicy
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** Одна синхронизация по BLE и слияние её результата с кэшем (правила те же, что по Wi-Fi). */
object BleSync {
    /** Часы читалки поправляются, если расходятся больше чем на столько секунд. */
    const val CLOCK_TOLERANCE_SEC = 60L
    const val BOOKS_PAGE_SIZE = 100
    private const val DAY_MS = 24 * 3600 * 1000L

    /** Id шагов в порядке выполнения (для onStep и [BleUiState.step]). */
    val STEPS = listOf("hello", "auth", "info", "time", "stats", "sessions", "highlights", "vocab", "books", "screenshots")

    /**
     * hello → auth → info → time (если часы расходятся > 60 с) → stats → sessions{since} →
     * highlights{since} → vocab → books (страницами по 100) → screenshots (только список).
     *
     * Отказ auth и сбои канала (таймаут, разрыв, повреждённые кадры) прерывают синхронизацию
     * исключением; ответ-ошибка одного шага записывается в `failures`, и идёт следующий шаг.
     * Шаги вне `info.features` пропускаются.
     */
    suspend fun run(
        client: BleCommandClient,
        plan: BleSyncPlan,
        onStep: (String) -> Unit = {},
    ): BleSyncResult {
        val failures = mutableListOf<String>()

        suspend fun <T> step(name: String, op: suspend () -> T): T? {
            onStep(name)
            return try {
                op()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BleError) {
                when {
                    e.isLinkFailure -> throw e
                    e is BleError.Remote && e.isNotSupported -> null
                    else -> {
                        failures += "$name: ${e.message}"
                        null
                    }
                }
            } catch (e: Exception) {
                failures += "$name: ${e.message}"
                null
            }
        }

        var result = BleSyncResult(sessionsSince = plan.sessionsSince, highlightsSince = plan.highlightsSince)

        if (!BleAuth.isValidToken(plan.token)) throw BleError.NoToken
        onStep("hello")
        val nonce = client.hello(plan.cnonce)
        onStep("auth")
        val proof = BleAuth.proof(plan.token, nonce) ?: throw BleError.Remote(401, "invalid nonce")
        val reply = client.authorizeReply(proof)
        // Взаимная аутентификация: читалка доказывает знание того же ключа по nonce телефона.
        val sproof = reply.sproof
        if (sproof != null) {
            if (!BleAuth.verifyServerProof(plan.token, plan.cnonce, nonce, sproof)) {
                throw BleServerProofException(BleServerProofException.Kind.MISMATCH)
            }
            result = result.copy(serverVerified = true)
        } else if (plan.requireServerProof) {
            // Прошивка 1.1.1 sproof не шлёт; но читалка, однажды его приславшая, без него — подмена.
            throw BleServerProofException(BleServerProofException.Kind.MISSING)
        }

        val info = step("info") { client.info() } ?: return result.copy(failures = failures)
        if (!ConnectionPolicy.sameReader(plan.expectedSerial, info.serial, info.deviceId)) {
            throw BleServerProofException(BleServerProofException.Kind.WRONG_READER)
        }
        result = result.copy(info = info)

        val now = plan.nowMs / 1000
        if (info.clock.available && (!info.clock.valid || abs(info.clock.epoch - now) > CLOCK_TOLERANCE_SEC)) {
            onStep("time")
            try {
                client.setTime(now, plan.timeZone.getOffset(plan.nowMs) / 60_000)
                result = result.copy(clockSet = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BleError) {
                when {
                    e.isLinkFailure -> throw e
                    e is BleError.Remote && e.status == 501 -> Unit // на плате нет RTC
                    else -> failures += "time: ${e.message}"
                }
            } catch (e: Exception) {
                failures += "time: ${e.message}"
            }
        }

        if (info.supports("stats")) {
            result = result.copy(stats = step("stats") { client.stats() })
            result = result.copy(sessions = step("sessions") { client.sessions(plan.sessionsSince) })
        }
        if (info.supports("highlights")) {
            result = result.copy(highlights = step("highlights") { client.highlights(since = plan.highlightsSince) })
        }
        if (info.supports("vocab")) {
            result = result.copy(vocab = step("vocab") { client.vocab() })
        }
        if (info.supports("library")) {
            result = result.copy(books = step("books") { client.books(BOOKS_PAGE_SIZE) })
        }
        if (info.supports("screenshots")) {
            result = result.copy(screenshots = step("screenshots") { client.screenshots() })
        }
        return result.copy(failures = failures)
    }

    /** Серийники совпадают или один из них неизвестен. */
    fun sameReader(expected: String?, reported: String?): Boolean = ConnectionPolicy.sameReader(expected, reported, null)

    /**
     * Курсор `since` для цитат (секунды): 0 (полный список), если кэш пуст или полного списка не
     * было больше суток — удалённые и правленые цитаты видны только в полном; иначе самая новая
     * известная, но не позже «сейчас».
     */
    fun highlightsCursor(highlights: List<Highlight>, lastFullMs: Long?, nowMs: Long = System.currentTimeMillis()): Long {
        if (highlights.isEmpty() || lastFullMs == null || nowMs - lastFullMs >= DAY_MS) return 0
        return minOf(highlights.maxOf { it.created }, nowMs / 1000)
    }

    /**
     * Вливает результат BLE в кэш по правилам Wi-Fi: сессии — без дублей и со сдвигом курсора;
     * полный список цитат заменяет кэш, инкрементальный сливается по id; stats, словарь, книги
     * и список скриншотов заменяются; info обновляется.
     */
    fun merge(
        result: BleSyncResult,
        snapshot: SyncSnapshot,
        sessions: List<ReadingSession>,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<SyncSnapshot, List<ReadingSession>> {
        var snap = snapshot
        var merged = sessions
        result.info?.let { snap = snap.copy(info = it) }
        result.stats?.let { snap = snap.copy(stats = it, statsFetchedAt = nowMs) }
        result.sessions?.let { incoming ->
            merged = SyncRules.mergeSessions(merged, incoming)
            // Часы, однажды ушедшие вперёд, не должны заморозить курсор в будущем.
            val lastStart = minOf(merged.maxOfOrNull { it.start } ?: 0, nowMs / 1000)
            snap = snap.copy(lastSessionStart = lastStart)
            if (result.sessionsSince == 0L) snap = snap.copy(lastFullSessionSync = nowMs)
        }
        result.highlights?.let { incoming ->
            snap = if (result.highlightsSince == 0L) {
                snap.copy(highlights = incoming, lastFullHighlightSync = nowMs)
            } else {
                snap.copy(highlights = mergeHighlights(snap.highlights, incoming))
            }
        }
        result.vocab?.let { snap = snap.copy(vocab = it) }
        result.books?.let { snap = snap.copy(books = it) }
        result.screenshots?.let { snap = snap.copy(screenshots = it) }
        snap = snap.copy(lastSync = nowMs)
        return snap to merged
    }

    /** Новые копии заменяют закэшированные с тем же ключом; новые добавляются в порядке читалки. */
    fun mergeHighlights(existing: List<Highlight>, incoming: List<Highlight>): List<Highlight> {
        val result = existing.toMutableList()
        val index = HashMap<String, Int>()
        existing.forEachIndexed { i, h -> index.putIfAbsent(h.key, i) }
        for (h in incoming) {
            val i = index[h.key]
            if (i != null) {
                result[i] = h
            } else {
                index[h.key] = result.size
                result += h
            }
        }
        return result
    }
}
