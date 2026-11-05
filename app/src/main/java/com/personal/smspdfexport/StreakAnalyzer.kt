package com.personal.smspdfexport

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Per-day, per-sender message counts over a streak window, plus combined and per-sender totals/averages. */
object StreakAnalyzer {

    private const val SELF_KEY = "__self__"

    data class SenderBreakdown(
        val senderKey: String,
        val label: String,
        val total: Int,
        val averagePerDay: Double
    )

    data class Analytics(
        val days: List<LocalDate>,
        /** One map per day in `days`, senderKey -> message count that day. */
        val dailyCounts: List<Map<String, Int>>,
        val combinedTotal: Int,
        val combinedAveragePerDay: Double,
        /** Self first, then others ordered by total descending. */
        val senderBreakdowns: List<SenderBreakdown>
    )

    fun compute(
        messages: List<SmsMessage>,
        streakStartDate: LocalDate,
        streakEndDate: LocalDate,
        selfLabel: String,
        resolveName: (String) -> String,
        zone: ZoneId = ZoneId.systemDefault()
    ): Analytics {
        val days = generateSequence(streakStartDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(streakEndDate) }
            .toList()
        val dayCount = days.size.coerceAtLeast(1)

        fun senderKey(m: SmsMessage) = if (m.isSent) SELF_KEY else m.address
        fun senderLabel(m: SmsMessage) = if (m.isSent) selfLabel else resolveName(m.address).ifBlank { m.address }

        val perDayPerSender = HashMap<LocalDate, HashMap<String, Int>>()
        val totalPerSender = HashMap<String, Int>()
        val labelPerSender = HashMap<String, String>()

        for (message in messages) {
            val day = Instant.ofEpochMilli(message.date).atZone(zone).toLocalDate()
            if (day.isBefore(streakStartDate) || day.isAfter(streakEndDate)) continue

            val key = senderKey(message)
            labelPerSender.getOrPut(key) { senderLabel(message) }
            perDayPerSender.getOrPut(day) { HashMap() }.merge(key, 1, Int::plus)
            totalPerSender.merge(key, 1, Int::plus)
        }

        val dailyCounts = days.map { day -> perDayPerSender[day]?.toMap() ?: emptyMap() }
        val combinedTotal = totalPerSender.values.sum()
        val combinedAverage = combinedTotal.toDouble() / dayCount

        val orderedKeys = totalPerSender.keys.sortedWith(
            compareBy({ it != SELF_KEY }, { -(totalPerSender[it] ?: 0) })
        )
        val breakdowns = orderedKeys.map { key ->
            val total = totalPerSender[key] ?: 0
            SenderBreakdown(
                senderKey = key,
                label = labelPerSender[key] ?: key,
                total = total,
                averagePerDay = total.toDouble() / dayCount
            )
        }

        return Analytics(days, dailyCounts, combinedTotal, combinedAverage, breakdowns)
    }
}
