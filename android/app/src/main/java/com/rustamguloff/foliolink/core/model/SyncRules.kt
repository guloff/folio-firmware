package com.rustamguloff.foliolink.core.model

// Правила слияния, общие для синхронизации по Wi-Fi и по Bluetooth.

object SyncRules {
    private const val DAY_MS = 24 * 3600 * 1000L

    /** Сессии без дублей (по ReadingSession.id), по возрастанию (start, book). */
    fun mergeSessions(existing: List<ReadingSession>, incoming: List<ReadingSession>): List<ReadingSession> {
        val seen = existing.mapTo(HashSet()) { it.id }
        val result = existing.toMutableList()
        for (s in incoming) if (seen.add(s.id)) result += s
        return result.sortedWith(compareBy({ it.start }, { it.book }))
    }

    /**
     * Курсор `since` для сессий: 0 (полная выгрузка), если кэш пуст или полной выгрузки не было
     * больше суток; иначе последний известный start, но не позже «сейчас» (часы читалки,
     * однажды ушедшие вперёд, не должны заморозить курсор в будущем).
     */
    fun sessionCursor(
        sessions: List<ReadingSession>,
        lastStart: Long,
        lastFullSync: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        if (sessions.isEmpty()) return 0
        if (lastFullSync == null || nowMs - lastFullSync >= DAY_MS) return 0
        return minOf(lastStart, nowMs / 1000)
    }
}
