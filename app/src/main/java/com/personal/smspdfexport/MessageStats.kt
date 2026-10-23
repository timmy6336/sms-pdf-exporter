package com.personal.smspdfexport

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Activity stats for a conversation, independent of any export date range. */
object MessageStats {

    data class Result(val daysSinceLastMessage: Long, val streakDays: Int)

    /**
     * daysSinceLastMessage: calendar days between today and the most recent
     * message (0 if a message landed today).
     *
     * streakDays: consecutive calendar days with at least one message,
     * counted backward from an anchor day — with a one-day buffer: if today
     * has no message yet, the streak anchors on yesterday instead of
     * breaking immediately, so a conversation that's active every day still
     * shows its real streak first thing in the morning before anyone's
     * texted. The streak is only actually broken once *two* days in a row
     * (today and yesterday) have nothing.
     */
    fun compute(timestamps: List<Long>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Result? {
        if (timestamps.isEmpty()) return null

        fun toLocalDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

        val messageDays = timestamps.mapTo(HashSet()) { toLocalDate(it) }
        val today = toLocalDate(now)
        val lastMessageDay = messageDays.maxOrNull() ?: return null

        val daysSinceLastMessage = ChronoUnit.DAYS.between(lastMessageDay, today)

        val yesterday = today.minusDays(1)
        val anchor = when {
            messageDays.contains(today) -> today
            messageDays.contains(yesterday) -> yesterday
            else -> null
        }

        var streakDays = 0
        var day = anchor
        while (day != null && messageDays.contains(day)) {
            streakDays += 1
            day = day.minusDays(1)
        }

        return Result(daysSinceLastMessage, streakDays)
    }
}
