// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.EtabliFocusApplication
import com.raban.etabli.focus.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Window(val label: String, val days: Int) {
    Week("week", 7),
    Month("month", 30),
}

@Composable
fun StatsScreen(app: EtabliFocusApplication) {
    val t = Coder.tokens
    var window by remember { mutableStateOf(Window.Week) }
    val categories by app.db.categoryDao().observeAll().collectAsState(initial = emptyList())

    val rangeStart = remember(window) {
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -(window.days - 1))
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val sessions by app.db.sessionDao()
        .observeCompletedFocusSince(rangeStart)
        .collectAsState(initial = emptyList())

    val totalMinutes = sessions.sumOf { s ->
        val end = s.actualEndEpoch ?: s.plannedEndEpoch
        ((end - s.startedAtEpoch) / 60_000L).toInt()
    }
    val totalBlocks  = sessions.size
    val perDay = remember(sessions, window) {
        val buckets = IntArray(window.days)
        sessions.forEach { s ->
            val daysFromAnchor = ((s.startedAtEpoch - rangeStart) / (24L * 60 * 60 * 1000)).toInt()
            if (daysFromAnchor in buckets.indices) buckets[daysFromAnchor]++
        }
        buckets.toList()
    }
    val perCategory = remember(sessions, categories) {
        sessions.groupBy { it.categoryID }
            .mapValues { (_, list) -> list.size }
            .toList()
            .sortedByDescending { it.second }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("stats", window.label))

        Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
            Window.values().forEach { w ->
                SegmentChip(label = w.label, selected = w == window, onClick = { window = w })
            }
        }

        Card(title = "totals", icon = Icons.Default.Insights) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatColumn("blocks", totalBlocks.toString())
                StatColumn("minutes", totalMinutes.toString())
                StatColumn("days", window.days.toString())
            }
        }

        Card(title = "blocks / day", icon = Icons.Default.BarChart) {
            BarChartCanvas(perDay, t.color.accent, t.color.hairline)
            Spacer(Modifier.height(t.space.sm))
            DayAxisLabels(rangeStart, window.days)
        }

        Card(title = "by category") {
            if (perCategory.isEmpty()) {
                MonoLabel("no sessions yet in this window.", color = t.color.faint)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(t.space.sm)) {
                    perCategory.forEach { (catId, count) ->
                        val cat = categories.firstOrNull { it.id == catId }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
                            ColorDot(cat?.color() ?: t.color.faint)
                            Text(cat?.name ?: "(unknown)", style = t.font.body,
                                 modifier = Modifier.weight(1f))
                            MonoLabel(count.toString())
                        }
                    }
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
private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(t.radius.sm))
            .background(if (selected) t.color.accent.copy(alpha = 0.12f) else t.color.surface)
            .border(
                1.dp,
                if (selected) t.color.accent else t.color.hairline,
                RoundedCornerShape(t.radius.sm),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = t.space.md, vertical = t.space.sm),
    ) {
        Text(
            label,
            style = t.font.caption.copy(color = if (selected) t.color.accent else t.color.faint),
        )
    }
}

@Composable
private fun BarChartCanvas(values: List<Int>, barColor: Color, gridColor: Color) {
    val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        val gap = 6f
        val barWidth = (size.width - gap * (values.size - 1)) / values.size
        // baseline
        drawLine(
            color = gridColor,
            start = Offset(0f, size.height - 1),
            end   = Offset(size.width, size.height - 1),
            strokeWidth = 1f,
        )
        values.forEachIndexed { i, v ->
            val ratio = v / maxValue.toFloat()
            val h = size.height * ratio
            drawRect(
                color = barColor.copy(alpha = if (v == 0) 0.15f else 1f),
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h.coerceAtLeast(2f)),
            )
        }
    }
}

@Composable
private fun DayAxisLabels(rangeStart: Long, days: Int) {
    val t = Coder.tokens
    val fmt = remember { SimpleDateFormat("d", Locale.US) }
    Row(modifier = Modifier.fillMaxWidth()) {
        repeat(days) { i ->
            val day = Calendar.getInstance().apply {
                timeInMillis = rangeStart
                add(Calendar.DAY_OF_YEAR, i)
            }
            Text(
                fmt.format(day.time),
                style = t.font.caption.copy(color = t.color.faint),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
