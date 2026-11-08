// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.FocusApp
import com.raban.etabli.focus.engine.dailySessionCounts
import com.raban.etabli.focus.engine.focusMinutes
import com.raban.etabli.focus.engine.targetProgress
import com.raban.etabli.focus.ui.rememberLocalDay
import com.raban.etabli.focus.ui.theme.Card
import com.raban.etabli.focus.ui.theme.Coder
import com.raban.etabli.focus.ui.theme.ColorDot
import com.raban.etabli.focus.ui.theme.FocusIcons
import com.raban.etabli.focus.ui.theme.MonoLabel
import com.raban.etabli.focus.ui.theme.PromptHeader
import com.raban.etabli.focus.ui.theme.SegmentChip

private enum class Window(val label: String, val days: Int) {
    Week("week", 7),
    Month("month", 30),
}

@Composable
fun StatsScreen(app: FocusApp) {
    val t = Coder.tokens
    var window by remember { mutableStateOf(Window.Week) }
    val categories by remember { app.db.categoryDao().observeAll() }.collectAsState(initial = emptyList())
    val templates by remember { app.db.templateDao().observeAll() }.collectAsState(initial = emptyList())

    val today = rememberLocalDay()
    val firstDay = today.date.minusDays((window.days - 1).toLong())
    val rangeStart = firstDay.atStartOfDay(today.zone).toInstant().toEpochMilli()

    val sessions by remember(rangeStart) {
        app.db.sessionDao().observeCompletedFocusSince(rangeStart)
    }.collectAsState(initial = emptyList())

    val perDay = remember(sessions, firstDay, window, today.zone) {
        dailySessionCounts(sessions.map { it.startedAtEpoch }, firstDay, window.days, today.zone)
    }
    val progress = remember(categories, templates, sessions, firstDay, window) {
        targetProgress(categories, templates, sessions, firstDay, window.days)
    }
    val knownIds = remember(categories) { categories.map { it.id }.toSet() }
    val orphaned = sessions.count { it.categoryID !in knownIds }
    val planned = progress.sumOf { it.target }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.xl),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("stats", window.label))

        Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
            Window.entries.forEach { w ->
                SegmentChip(label = "last ${w.days} days", selected = w == window, onClick = { window = w })
            }
        }

        Card(title = "totals", icon = Icons.Default.Info) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                StatColumn("blocks", sessions.size.toString())
                StatColumn("planned", planned.toString())
                StatColumn("minutes", focusMinutes(sessions).toString())
                StatColumn("days", window.days.toString())
            }
        }

        Card(title = "against targets", icon = Icons.Default.Check) {
            MonoLabel(
                "completed focus blocks vs. the weekly template, summed over each day in the window.",
                color = t.color.faint,
            )
            if (progress.isEmpty() && orphaned == 0) {
                MonoLabel("no targets and no sessions in this window.", color = t.color.faint)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(t.space.md)) {
                    progress.forEach { TargetRow(it) }
                    if (orphaned > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
                            ColorDot(t.color.faint)
                            Text("(deleted category)", style = t.font.body, modifier = Modifier.weight(1f))
                            MonoLabel(orphaned.toString(), color = t.color.faint)
                        }
                    }
                }
            }
        }

        Card(title = "blocks / day", icon = FocusIcons.BarChart) {
            BarChartCanvas(perDay, t.color.accent, t.color.hairline)
            Spacer(Modifier.height(t.space.sm))
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(window.days) { i ->
                    Text(
                        firstDay.plusDays(i.toLong()).dayOfMonth.toString(),
                        style = t.font.caption.copy(color = t.color.faint),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    val t = Coder.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = t.font.display)
        MonoLabel(label, color = t.color.faint)
    }
}

@Composable
private fun BarChartCanvas(values: List<Int>, barColor: Color, gridColor: Color) {
    val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        val gap = 6f
        val barWidth = (size.width - gap * (values.size - 1)) / values.size
        drawLine(
            color = gridColor,
            start = Offset(0f, size.height - 1),
            end = Offset(size.width, size.height - 1),
            strokeWidth = 1f,
        )
        values.forEachIndexed { i, v ->
            val h = size.height * (v / maxValue.toFloat())
            drawRect(
                color = barColor.copy(alpha = if (v == 0) 0.15f else 1f),
                topLeft = Offset(i * (barWidth + gap), size.height - h.coerceAtLeast(2f)),
                size = Size(barWidth, h.coerceAtLeast(2f)),
            )
        }
    }
}
