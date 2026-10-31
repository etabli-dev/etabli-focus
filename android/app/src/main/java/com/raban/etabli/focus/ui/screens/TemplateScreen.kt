// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.EtabliFocusApplication
import com.raban.etabli.focus.data.TemplateEntryEntity
import com.raban.etabli.focus.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Calendar

// java.util.Calendar weekdays: SUNDAY=1 ... SATURDAY=7
private val Weekdays = listOf(
    2 to "Monday", 3 to "Tuesday", 4 to "Wednesday", 5 to "Thursday",
    6 to "Friday", 7 to "Saturday", 1 to "Sunday",
)

@Composable
fun TemplateScreen(app: EtabliFocusApplication) {
    val t = Coder.tokens
    val scope = rememberCoroutineScope()
    val categories by app.db.categoryDao().observeAll().collectAsState(initial = emptyList())
    val templates by app.db.templateDao().observeAll().collectAsState(initial = emptyList())
    val today = remember { Calendar.getInstance().get(Calendar.DAY_OF_WEEK) }

    val lookup = remember(templates) {
        templates.associateBy { it.weekday to it.categoryID }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("template", "weekly"))
        MonoLabel("target focus blocks per category, per weekday.", color = t.color.faint)

        Card(title = "weekly plan", icon = Icons.Default.CalendarMonth) {
            if (categories.isEmpty()) {
                EmptyState("No categories yet. Add some on the Categories tab.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(t.space.lg)) {
                    categories.forEach { cat ->
                        Column(verticalArrangement = Arrangement.spacedBy(t.space.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                                ColorDot(cat.color())
                                Text(cat.name, style = t.font.headline)
                            }
                            WeekdayTargets(
                                categoryName = cat.name,
                                targets = Weekdays.associate { (wd, _) -> wd to (lookup[wd to cat.id]?.targetBlocks ?: 0) },
                                today = today,
                                onChange = { weekday, newValue ->
                                    scope.launch {
                                        app.db.templateDao().upsert(
                                            TemplateEntryEntity(
                                                weekday = weekday,
                                                categoryID = cat.id,
                                                targetBlocks = newValue,
                                            )
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
internal fun WeekdayTargets(
    categoryName: String,
    targets: Map<Int, Int>,
    today: Int,
    onChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Coder.tokens
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Two 48 dp targets, their gap and cell padding must fit before adding a column.
        val minimumCellWidth = 116.dp
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
            TargetButton("−", "Decrease $name target for $categoryName", enabled = value > 0) { onChange(value - 1) }
            TargetButton("+", "Increase $name target for $categoryName", enabled = value < 8) { onChange(value + 1) }
        }
    }
}

@Composable
private fun TargetButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(t.radius.sm))
            .background(t.color.paper)
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = t.font.caption, color = if (enabled) t.color.ink else t.color.faint,
            modifier = Modifier.clearAndSetSemantics { })
    }
}
