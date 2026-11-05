package com.personal.smspdfexport

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * "Fun fact" style insights computed from a conversation's entire history —
 * lifetime totals, when it started, its best-ever streak, and lightweight
 * behavioral patterns (busiest day/hour, typical reply speed).
 */
object ConversationInsights {

    private const val SELF_KEY = "__self__"
    private const val MIN_REPLY_GAP_MINUTES = 1L
    private const val MAX_REPLY_GAP_MINUTES = 12L * 60L // ignore gaps over 12h — that's a new conversation, not a "reply"

    data class SenderTotal(
        val senderKey: String,
        val label: String,
        val total: Int,
        /** Null when there weren't enough sender-switch samples to form a median. */
        val medianReplyMinutes: Double?
    )

    data class Result(
        val totalMessages: Int,
        val firstMessageDate: LocalDate,
        val longestStreakDays: Int,
        val busiestWeekdayLabel: String?,
        val busiestHourLabel: String?,
        /** Self first, then others ordered by total descending. */
        val senderTotals: List<SenderTotal>
    )

    fun compute(
        messages: List<SmsMessage>,
        selfLabel: String,
        resolveName: (String) -> String,
        zone: ZoneId = ZoneId.systemDefault()
    ): Result? {
        if (messages.isEmpty()) return null
        val sorted = messages.sortedBy { it.date }

        fun senderKey(m: SmsMessage) = if (m.isSent) SELF_KEY else m.address
        fun senderLabel(m: SmsMessage) = if (m.isSent) selfLabel else resolveName(m.address).ifBlank { m.address }

        val totalPerSender = HashMap<String, Int>()
        val labelPerSender = HashMap<String, String>()
        val messageDays = HashSet<LocalDate>()
        val weekdayCounts = HashMap<DayOfWeek, Int>()
        val hourCounts = HashMap<Int, Int>()
        val replySamples = HashMap<String, MutableList<Long>>()

        var previous: SmsMessage? = null
        for (message in sorted) {
            val key = senderKey(message)
            totalPerSender.merge(key, 1, Int::plus)
            labelPerSender.getOrPut(key) { senderLabel(message) }

            val zoned = Instant.ofEpochMilli(message.date).atZone(zone)
            messageDays += zoned.toLocalDate()
            weekdayCounts.merge(zoned.dayOfWeek, 1, Int::plus)
            hourCounts.merge(zoned.hour, 1, Int::plus)

            val prev = previous
            if (prev != null && senderKey(prev) != key) {
                val gapMinutes = (message.date - prev.date) / 60_000L
                if (gapMinutes in MIN_REPLY_GAP_MINUTES..MAX_REPLY_GAP_MINUTES) {
                    replySamples.getOrPut(key) { mutableListOf() }.add(gapMinutes)
                }
            }
            previous = message
        }

        val orderedKeys = totalPerSender.keys.sortedWith(
            compareBy({ it != SELF_KEY }, { -(totalPerSender[it] ?: 0) })
        )
        val senderTotals = orderedKeys.map { key ->
            SenderTotal(
                senderKey = key,
                label = labelPerSender[key] ?: key,
                total = totalPerSender[key] ?: 0,
                medianReplyMinutes = replySamples[key]?.let { median(it) }
            )
        }

        return Result(
            totalMessages = sorted.size,
            firstMessageDate = Instant.ofEpochMilli(sorted.first().date).atZone(zone).toLocalDate(),
            longestStreakDays = longestStreak(messageDays),
            busiestWeekdayLabel = weekdayCounts.maxByOrNull { it.value }?.key
                ?.getDisplayName(TextStyle.FULL, Locale.getDefault()),
            busiestHourLabel = hourCounts.maxByOrNull { it.value }?.key?.let { formatHourRange(it) },
            senderTotals = senderTotals
        )
    }

    private fun longestStreak(days: Set<LocalDate>): Int {
        if (days.isEmpty()) return 0
        val sortedDays = days.sorted()
        var longest = 1
        var current = 1
        for (i in 1 until sortedDays.size) {
            current = if (sortedDays[i] == sortedDays[i - 1].plusDays(1)) current + 1 else 1
            longest = maxOf(longest, current)
        }
        return longest
    }

    private fun median(values: List<Long>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2].toDouble() else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun formatHourRange(hour: Int): String {
        fun label(h: Int): String {
            val period = if (h < 12) "AM" else "PM"
            val display = if (h % 12 == 0) 12 else h % 12
            return "$display $period"
        }
        return "${label(hour)} – ${label((hour + 1) % 24)}"
    }

    /** Minutes -> a short human label like "8 min", "2.3 hr", or "<1 min". */
    fun formatReplyMinutes(minutes: Double): String = when {
        minutes < 1 -> "<1 min"
        minutes < 60 -> "${minutes.toInt()} min"
        else -> String.format(Locale.getDefault(), "%.1f hr", minutes / 60.0)
    }
}
