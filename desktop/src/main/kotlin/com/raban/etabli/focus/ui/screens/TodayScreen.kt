// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.FocusApp
import com.raban.etabli.focus.data.CategoryEntity
import com.raban.etabli.focus.data.SessionEntity
import com.raban.etabli.focus.engine.TargetProgress
import com.raban.etabli.focus.engine.TimerKind
import com.raban.etabli.focus.engine.TimerPhase
import com.raban.etabli.focus.engine.TimerState
import com.raban.etabli.focus.engine.focusMinutes
import com.raban.etabli.focus.engine.progress
import com.raban.etabli.focus.engine.remainingSeconds
import com.raban.etabli.focus.engine.targetProgress
import com.raban.etabli.focus.ui.clockText
import com.raban.etabli.focus.ui.rememberLocalDay
import com.raban.etabli.focus.ui.rememberNow
import com.raban.etabli.focus.ui.theme.Card
import com.raban.etabli.focus.ui.theme.Coder
import com.raban.etabli.focus.ui.theme.ColorDot
import com.raban.etabli.focus.ui.theme.DangerButton
import com.raban.etabli.focus.ui.theme.EmptyState
import com.raban.etabli.focus.ui.theme.FocusIcons
import com.raban.etabli.focus.ui.theme.HorizontalHairline
import com.raban.etabli.focus.ui.theme.ListRow
import com.raban.etabli.focus.ui.theme.MonoLabel
import com.raban.etabli.focus.ui.theme.PrimaryButton
import com.raban.etabli.focus.ui.theme.ProgressBar
import com.raban.etabli.focus.ui.theme.PromptHeader
import com.raban.etabli.focus.ui.theme.SecondaryButton
import com.raban.etabli.focus.ui.theme.StepButton
import com.raban.etabli.focus.ui.theme.categoryColor
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(app: FocusApp) {
    val t = Coder.tokens
    val categories by remember { app.db.categoryDao().observeAll() }.collectAsState(initial = emptyList())
    val templates by remember { app.db.templateDao().observeAll() }.collectAsState(initial = emptyList())
    val today = rememberLocalDay()
    val sessionsToday by remember(today.startMillis) {
        app.db.sessionDao().observeCompletedFocusSince(today.startMillis)
    }.collectAsState(initial = emptyList())
    val state by app.engine.state.collectAsState()
    val timerError by app.engine.error.collectAsState()
    var startCategory by remember { mutableStateOf<CategoryEntity?>(null) }

    val plan = remember(categories, templates, sessionsToday, today) {
        targetProgress(categories, templates, sessionsToday, today.date, days = 1)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(t.color.paper)) {
        val wide = maxWidth >= 860.dp
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.space.xl),
            verticalArrangement = Arrangement.spacedBy(t.space.lg),
        ) {
            PromptHeader(listOf("today", today.date.toString()))
            timerError?.let { Text(it, style = t.font.body.copy(color = t.color.danger)) }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(t.space.lg)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(t.space.lg)) {
                        TimerColumn(app, state, categories)
                        TodayLog(sessionsToday, categories, today.zone)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(t.space.lg)) {
                        PlanCard(plan)
                        StartBlockCard(categories, enabled = state.phase == TimerPhase.Idle) { startCategory = it }
                    }
                }
            } else {
                TimerColumn(app, state, categories)
                PlanCard(plan)
                StartBlockCard(categories, enabled = state.phase == TimerPhase.Idle) { startCategory = it }
                TodayLog(sessionsToday, categories, today.zone)
            }
        }
    }

    startCategory?.let { category ->
        StartBlockDialog(
            category = category,
            onDismiss = { startCategory = null },
            onStart = { minutes ->
                app.engine.start(category.id, TimerKind.Focus, minutes)
                startCategory = null
            },
        )
    }
}

@Composable
private fun TimerColumn(app: FocusApp, state: TimerState, categories: List<CategoryEntity>) {
    val t = Coder.tokens
    Column(verticalArrangement = Arrangement.spacedBy(t.space.lg)) {
        Text(
            when (state.phase) {
                TimerPhase.Idle -> "Ready"
                TimerPhase.Running -> "Focusing"
                TimerPhase.Paused -> "Paused"
            },
            style = t.font.display,
        )
        Card(title = "now", icon = Icons.Default.PlayArrow) {
            if (state.phase == TimerPhase.Idle) {
                MonoLabel("Pick a category to start a focus block.", color = t.color.faint)
            } else {
                LiveTimer(app, state, categories.firstOrNull { it.id == state.categoryID })
            }
        }
    }
}

@Composable
private fun LiveTimer(app: FocusApp, state: TimerState, category: CategoryEntity?) {
    val t = Coder.tokens
    val now by rememberNow()
    val remaining = state.remainingSeconds(now)
    val progress = state.progress(now)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.space.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (category != null) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                ColorDot(categoryColor(category.colorHex))
                MonoLabel(category.name)
            }
        }
        Box(modifier = Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            val strokePx = 12f
            Canvas(modifier = Modifier.fillMaxSize()) {
                val diameter = size.minDimension - strokePx
                val offset = (size.minDimension - diameter) / 2
                drawCircle(color = t.color.hairline, radius = diameter / 2, style = Stroke(width = strokePx))
                drawArc(
                    color = t.color.accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(offset, offset),
                    size = Size(diameter, diameter),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(clockText(remaining), style = t.font.display)
                MonoLabel(if (state.phase == TimerPhase.Paused) "paused" else "running", color = t.color.faint)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
            when (state.phase) {
                TimerPhase.Running -> PrimaryButton("Pause", icon = FocusIcons.Pause) { app.engine.pause() }
                TimerPhase.Paused -> PrimaryButton("Resume", icon = Icons.Default.PlayArrow) { app.engine.resume() }
                TimerPhase.Idle -> Unit
            }
            DangerButton("Reset", icon = FocusIcons.Stop) { app.engine.cancel() }
        }
        if (state.phase == TimerPhase.Paused) {
            MonoLabel("the remaining time is kept while paused, also across restarts.", color = t.color.faint)
        }
    }
}

@Composable
private fun PlanCard(plan: List<TargetProgress>) {
    val t = Coder.tokens
    Card(title = "today's plan", icon = Icons.Default.DateRange) {
        if (plan.isEmpty()) {
            MonoLabel(
                "no plan for today — open Template to set targets per category per weekday.",
                color = t.color.faint,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(t.space.md)) {
                plan.forEach { TargetRow(it) }
            }
        }
    }
}

@Composable
internal fun TargetRow(row: TargetProgress) {
    val t = Coder.tokens
    val color = categoryColor(row.category.colorHex)
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
        ColorDot(color)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(t.space.xs)) {
            Text(row.category.name, style = t.font.body, maxLines = 1)
            ProgressBar(row.fraction, color)
        }
        MonoLabel("${row.completed} / ${row.target}", color = if (row.met) t.color.accent else t.color.faint)
    }
}

@Composable
private fun StartBlockCard(categories: List<CategoryEntity>, enabled: Boolean, onPick: (CategoryEntity) -> Unit) {
    val t = Coder.tokens
    Card(title = "start a block", icon = Icons.Default.PlayArrow) {
        if (!enabled) {
            MonoLabel("a block is active — pause or reset it before starting another.", color = t.color.faint)
        }
        Column {
            categories.forEachIndexed { idx, cat ->
                ListRow(
                    title = cat.name,
                    metadata = "${cat.defaultFocusMinutes} min focus · ${cat.defaultBreakMinutes} min break",
                    leading = { ColorDot(categoryColor(cat.colorHex)) },
                    onClick = if (enabled) ({ onPick(cat) }) else null,
                )
                if (idx < categories.lastIndex) HorizontalHairline()
            }
        }
    }
}

private val TimeOfDay = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun TodayLog(sessions: List<SessionEntity>, categories: List<CategoryEntity>, zone: java.time.ZoneId) {
    val t = Coder.tokens
    val ordered = remember(sessions) { sessions.sortedBy { it.startedAtEpoch } }
    Card(title = "completed today", icon = FocusIcons.BarChart) {
        if (ordered.isEmpty()) {
            EmptyState("no completed blocks yet", "Finished blocks are listed here.")
        } else {
            MonoLabel("${ordered.size} blocks · ${focusMinutes(ordered)} min", color = t.color.faint)
            Column {
                ordered.forEachIndexed { idx, session ->
                    val category = categories.firstOrNull { it.id == session.categoryID }
                    val start = Instant.ofEpochMilli(session.startedAtEpoch).atZone(zone).format(TimeOfDay)
                    ListRow(
                        title = category?.name ?: "(deleted category)",
                        metadata = "$start · ${focusMinutes(listOf(session))} min",
                        leading = { ColorDot(category?.let { categoryColor(it.colorHex) } ?: t.color.faint) },
                    )
                    if (idx < ordered.lastIndex) HorizontalHairline()
                }
            }
        }
    }
}

@Composable
private fun StartBlockDialog(category: CategoryEntity, onDismiss: () -> Unit, onStart: (Int) -> Unit) {
    val t = Coder.tokens
    var minutes by remember(category.id) { mutableIntStateOf(category.defaultFocusMinutes.coerceIn(1, 1440)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.color.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                ColorDot(categoryColor(category.colorHex))
                Text(category.name, style = t.font.title)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.space.md)) {
                MonoLabel("focus block", color = t.color.faint)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
                    MonoLabel("$minutes min")
                    StepButton("−", "Shorter by 5 minutes", enabled = minutes > 5) { minutes -= 5 }
                    StepButton("+", "Longer by 5 minutes", enabled = minutes < 120) { minutes += 5 }
                }
                MonoLabel("default: ${category.defaultFocusMinutes} min", color = t.color.faint)
            }
        },
        confirmButton = { PrimaryButton("Start focus", icon = Icons.Default.PlayArrow) { onStart(minutes) } },
        dismissButton = { SecondaryButton("Cancel", onDismiss) },
    )
}
