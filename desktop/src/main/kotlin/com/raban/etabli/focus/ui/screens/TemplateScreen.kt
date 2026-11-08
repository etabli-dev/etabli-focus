// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.FocusApp
import com.raban.etabli.focus.data.TemplateEntryEntity
import com.raban.etabli.focus.engine.calendarWeekday
import com.raban.etabli.focus.ui.rememberLocalDay
import com.raban.etabli.focus.ui.theme.Card
import com.raban.etabli.focus.ui.theme.Coder
import com.raban.etabli.focus.ui.theme.ColorDot
import com.raban.etabli.focus.ui.theme.EmptyState
import com.raban.etabli.focus.ui.theme.HorizontalHairline
import com.raban.etabli.focus.ui.theme.MonoLabel
import com.raban.etabli.focus.ui.theme.PromptHeader
import com.raban.etabli.focus.ui.theme.StepButton
import com.raban.etabli.focus.ui.theme.categoryColor
import kotlinx.coroutines.launch

// Calendar weekdays: SUNDAY=1 ... SATURDAY=7, listed Monday first.
private val Weekdays = listOf(
    2 to "Monday", 3 to "Tuesday", 4 to "Wednesday", 5 to "Thursday",
    6 to "Friday", 7 to "Saturday", 1 to "Sunday",
)

/** Same limit as Android: 0 to 8 blocks per category per weekday. */
private const val MAX_TARGET = 8

@Composable
fun TemplateScreen(app: FocusApp) {
    val t = Coder.tokens
    val scope = rememberCoroutineScope()
    val categories by remember { app.db.categoryDao().observeAll() }.collectAsState(initial = emptyList())
    val templates by remember { app.db.templateDao().observeAll() }.collectAsState(initial = emptyList())
    val today = calendarWeekday(rememberLocalDay().date)
    val lookup = remember(templates) { templates.associateBy { it.weekday to it.categoryID } }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.xl),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("template", "weekly"))
        MonoLabel("target focus blocks per category, per weekday.", color = t.color.faint)

        Card(title = "weekly plan", icon = Icons.Default.DateRange) {
            if (categories.isEmpty()) {
                EmptyState("No categories yet.", "Add some on the Categories tab.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(t.space.lg)) {
                    categories.forEach { cat ->
                        val weekly = Weekdays.sumOf { (wd, _) -> lookup[wd to cat.id]?.targetBlocks ?: 0 }
                        Column(verticalArrangement = Arrangement.spacedBy(t.space.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                                ColorDot(categoryColor(cat.colorHex))
                                Text(cat.name, style = t.font.headline, modifier = Modifier.weight(1f))
                                MonoLabel("$weekly / week", color = t.color.faint)
                            }
                            WeekdayTargets(
                                categoryName = cat.name,
                                targets = Weekdays.associate { (wd, _) -> wd to (lookup[wd to cat.id]?.targetBlocks ?: 0) },
                                today = today,
                                onChange = { weekday, newValue ->
                                    scope.launch {
                                        app.db.templateDao().upsert(
                                            TemplateEntryEntity(weekday = weekday, categoryID = cat.id, targetBlocks = newValue),
                                        )
                                    }
                                },
                            )
                        }
                        HorizontalHairline()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayTargets(
    categoryName: String,
    targets: Map<Int, Int>,
    today: Int,
    onChange: (Int, Int) -> Unit,
) {
    val t = Coder.tokens
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimumCellWidth = 104.dp
        val columns = ((maxWidth + t.space.sm) / (minimumCellWidth + t.space.sm))
            .toInt().coerceIn(1, Weekdays.size)
        val cellWidth = (maxWidth - t.space.sm * (columns - 1)) / columns
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(t.space.sm),
            verticalArrangement = Arrangement.spacedBy(t.space.sm),
            maxItemsInEachRow = columns,
        ) {
            Weekdays.forEach { (weekday, name) ->
                WeekdayCell(
                    name = name,
                    categoryName = categoryName,
                    value = targets[weekday] ?: 0,
                    highlight = weekday == today,
                    onChange = { onChange(weekday, it) },
                    modifier = Modifier.width(cellWidth),
                )
            }
        }
    }
}

@Composable
private fun WeekdayCell(
    name: String,
    categoryName: String,
    value: Int,
    highlight: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Coder.tokens
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.space.xs),
        modifier = modifier
            .clip(RoundedCornerShape(t.radius.sm))
            .background(if (highlight) t.color.accent.copy(alpha = 0.08f) else t.color.surface)
            .border(
                1.dp,
                if (highlight) t.color.accent else t.color.hairline,
                RoundedCornerShape(t.radius.sm),
            )
            .padding(t.space.sm),
    ) {
        Text(name.take(3).lowercase(), style = t.font.mono,
            color = if (highlight) t.color.accent else t.color.faint,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = if (highlight) "$name, today" else name
            })
        Text(value.toString(), style = t.font.headline,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "$name target for $categoryName"
                stateDescription = "$value blocks"
            })
        Row(horizontalArrangement = Arrangement.spacedBy(t.space.xs)) {
            StepButton("−", "Decrease $name target for $categoryName", enabled = value > 0) { onChange(value - 1) }
            StepButton("+", "Increase $name target for $categoryName", enabled = value < MAX_TARGET) { onChange(value + 1) }
        }
    }
}
