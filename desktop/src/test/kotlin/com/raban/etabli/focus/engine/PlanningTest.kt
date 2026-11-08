// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.engine

import com.raban.etabli.focus.data.CategoryEntity
import com.raban.etabli.focus.data.SessionEntity
import com.raban.etabli.focus.data.TemplateEntryEntity
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanningTest {
    private val research = CategoryEntity("research", "Research", "28A745", 50, 10, 0)
    private val admin = CategoryEntity("admin", "Admin", "6B6B6B", 25, 5, 1)
    private val idle = CategoryEntity("idle", "Unplanned", "1F6FEB", 25, 5, 2)
    private val monday = LocalDate.of(2026, 11, 2)

    private fun done(category: String) = SessionEntity(
        id = "$category-${Math.random()}", categoryID = category, startedAtEpoch = 0,
        plannedEndEpoch = 0, actualEndEpoch = 0, completed = true, kind = "focus",
    )

    // Monday=2 … Friday=6 research 2/day, Sunday=1 admin 1.
    private val templates = (2..6).map { TemplateEntryEntity(it, "research", 2) } +
        TemplateEntryEntity(1, "admin", 1)

    @Test fun weekdaysUseTheCalendarConventionSharedWithAndroidAndIos() {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(2, calendarWeekday(monday))
        assertEquals(7, calendarWeekday(monday.plusDays(5)))
        assertEquals(1, calendarWeekday(monday.plusDays(6)))
    }

    @Test fun todayPlanListsTargetsAndUnplannedWorkButNotIdleCategories() {
        val plan = targetProgress(listOf(research, admin, idle), templates, listOf(done("research"), done("admin")), monday, 1)
        assertEquals(listOf("research", "admin"), plan.map { it.category.id })
        assertEquals(2, plan[0].target)
        assertEquals(1, plan[0].completed)
        assertEquals(0.5f, plan[0].fraction)
        assertEquals(0, plan[1].target, "completed without a target still shows")
        assertEquals(0f, plan[1].fraction)
    }

    @Test fun aSevenDayWindowComparesAgainstExactlyOneWeekOfTargets() {
        // Any 7 consecutive days contain each weekday once.
        for (offset in 0L..6L) {
            val week = targetProgress(listOf(research, admin), templates, emptyList(), monday.plusDays(offset), 7)
            assertEquals(10, week.single { it.category == research }.target)
            assertEquals(1, week.single { it.category == admin }.target)
        }
        val met = targetProgress(listOf(research), templates, List(12) { done("research") }, monday, 7).single()
        assertTrue(met.met)
        assertEquals(1f, met.fraction, "progress is capped at the target")
    }

    @Test fun aThirtyDayWindowSumsEachCalendarDaysTarget() {
        // 2026-11-02 (Mon) .. 2026-12-01 (Tue): 22 weekdays Mon–Fri, 4 Sundays.
        val month = targetProgress(listOf(research, admin), templates, emptyList(), monday, 30)
        assertEquals(44, month.single { it.category == research }.target)
        assertEquals(4, month.single { it.category == admin }.target)
    }

    @Test fun minutesPreferActiveTimeAndFallBackToTheHistoricalSpan() {
        val measured = done("research").copy(startedAtEpoch = 0, actualEndEpoch = 90 * 60_000L, activeMillis = 25 * 60_000L)
        val historical = done("research").copy(startedAtEpoch = 0, actualEndEpoch = 30 * 60_000L + 59_999, activeMillis = null)
        assertEquals(25 + 30, focusMinutes(listOf(measured, historical)))
    }
}
