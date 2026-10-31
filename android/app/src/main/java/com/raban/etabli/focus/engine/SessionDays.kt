package com.raban.etabli.focus.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

fun dailySessionCounts(starts: List<Long>, firstDay: LocalDate, days: Int, zone: ZoneId): List<Int> {
    require(days in 1..366)
    val counts = IntArray(days)
    starts.forEach { epoch ->
        val date = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()
        val offset = ChronoUnit.DAYS.between(firstDay, date)
        if (offset in 0 until days.toLong()) counts[offset.toInt()]++
    }
    return counts.toList()
}
