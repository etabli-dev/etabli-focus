// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.ui.theme.CoderTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WeekdayTargetsTest {
    @get:Rule val compose = createComposeRule()
    private val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private fun render(width: Int, fontScale: Float = 1f, start: Int = 4) {
        val targets = mutableStateMapOf<Int, Int>().apply { (1..7).forEach { put(it, start) } }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CoderTheme {
                    // The screen and card each add 16 dp per edge in TemplateScreen.
                    Box(Modifier.width(width.dp).padding(32.dp)) {
                        WeekdayTargets("Work", targets, today = 2,
                            onChange = { day, value -> targets[day] = value },
                            modifier = Modifier.testTag("weekday-targets"))
                    }
                }
            }
        }
    }

    private fun assertTargetsFit() {
        val grid = compose.onNodeWithTag("weekday-targets").fetchSemanticsNode().boundsInRoot
        val bounds = days.flatMap { day ->
            listOf("Decrease", "Increase").map { action ->
                val node = compose.onNodeWithContentDescription("$action $day target for Work")
                node.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val box = node.fetchSemanticsNode().boundsInRoot
                assertTrue("$day action exceeds the grid", box.left >= grid.left && box.right <= grid.right + 0.5f)
                assertTrue("$day action exceeds the grid", box.top >= grid.top && box.bottom <= grid.bottom + 0.5f)
                box
            }
        }
        bounds.forEachIndexed { i, first ->
            bounds.drop(i + 1).forEach { second -> assertFalse("Action targets overlap", first.overlaps(second)) }
        }
    }

    @Test @Config(qualifiers = "w390dp-h844dp-mdpi")
    fun phoneTargetsStayVisibleAndDoNotOverlap() {
        render(390)
        assertTargetsFit()
    }

    @Test @Config(qualifiers = "w320dp-h844dp-mdpi")
    fun narrowPhoneRetainsTouchTargetsWithLargeText() {
        render(320, fontScale = 2f)
        assertTargetsFit()
    }

    @Test @Config(qualifiers = "w840dp-h900dp-mdpi")
    fun tabletTargetsUseTheAvailableWidthWithoutOverlap() {
        render(840)
        assertTargetsFit()
    }

    @Test @Config(qualifiers = "w390dp-h844dp-mdpi")
    fun actionsExposeTheirPurposeAndRespectBlockLimits() {
        render(390, start = 0)
        val decrease = compose.onNodeWithContentDescription("Decrease Monday target for Work")
        val increase = compose.onNodeWithContentDescription("Increase Monday target for Work")
        decrease.assertIsNotEnabled()
        repeat(8) { increase.performClick() }
        increase.assertIsNotEnabled()
        decrease.performClick()
        compose.onNodeWithContentDescription("Monday target for Work")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "7 blocks"))
        increase.assertIsEnabled()
    }
}
