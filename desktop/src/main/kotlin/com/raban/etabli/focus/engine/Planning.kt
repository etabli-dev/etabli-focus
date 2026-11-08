// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.engine

import com.raban.etabli.focus.data.CategoryEntity
import com.raban.etabli.focus.data.SessionEntity
import com.raban.etabli.focus.data.TemplateEntryEntity
import java.time.LocalDate

/** Weekday convention of template rows on every platform: 1 = Sunday … 7 = Saturday. */
fun calendarWeekday(date: LocalDate): Int = date.dayOfWeek.value % 7 + 1

data class TargetProgress(val category: CategoryEntity, val target: Int, val completed: Int) {
    val fraction: Float get() = if (target == 0) 0f else (completed.toFloat() / target).coerceAtMost(1f)
    val met: Boolean get() = target > 0 && completed >= target
}

/**
 * Completed focus blocks against the weekly template over the calendar days
 * `firstDay until firstDay + days`. Each day contributes its own weekday's
 * target, so a 7-day window compares against exactly one week of targets and
 * a 1-day window is Today's plan. Categories with neither a target nor a
 * completed block are left out, as on Android and iOS.
 *
 * [completedFocus] must already be limited to completed focus blocks inside
 * the window.
 */
fun targetProgress(
    categories: List<CategoryEntity>,
    templates: List<TemplateEntryEntity>,
    completedFocus: List<SessionEntity>,
    firstDay: LocalDate,
    days: Int,
): List<TargetProgress> {
    require(days in 1..366)
    val targets = templates.associate { (it.weekday to it.categoryID) to it.targetBlocks }
    val weekdays = (0 until days).map { calendarWeekday(firstDay.plusDays(it.toLong())) }
    return categories.mapNotNull { category ->
        val target = weekdays.sumOf { weekday -> targets[weekday to category.id] ?: 0 }
        val completed = completedFocus.count { it.categoryID == category.id }
        if (target == 0 && completed == 0) null else TargetProgress(category, target, completed)
    }
}

/** Focused minutes, preferring recorded active time over the historical span (Android rule). */
fun focusMinutes(sessions: List<SessionEntity>): Int = sessions.sumOf { s ->
    val end = s.actualEndEpoch ?: s.plannedEndEpoch
    ((s.activeMillis ?: (end - s.startedAtEpoch).coerceAtLeast(0)) / 60_000L).toInt()
}
