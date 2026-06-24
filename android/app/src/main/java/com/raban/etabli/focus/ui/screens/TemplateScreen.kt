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
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.EtabliFocusApplication
import com.raban.etabli.focus.data.TemplateEntryEntity
import com.raban.etabli.focus.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Calendar

// java.util.Calendar weekdays: SUNDAY=1 ... SATURDAY=7
private val Weekdays = listOf(
    2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu",
    6 to "Fri", 7 to "Sat", 1 to "Sun",
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
                            Row(horizontalArrangement = Arrangement.spacedBy(t.space.xs)) {
                                Weekdays.forEach { (wd, label) ->
                                    val current = lookup[wd to cat.id]?.targetBlocks ?: 0
                                    WeekdayCell(
                                        label = label,
                                        value = current,
                                        highlight = wd == today,
                                        onChange = { newVal ->
                                            scope.launch {
                                                app.db.templateDao().upsert(
                                                    TemplateEntryEntity(
                                                        weekday = wd,
                                                        categoryID = cat.id,
                                                        targetBlocks = newVal,
                                                    )
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        HorizontalHairline()
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdayCell(
    label: String,
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
            .padding(vertical = t.space.sm, horizontal = t.space.xs),
    ) {
        MonoLabel(label.lowercase(), color = if (highlight) t.color.accent else t.color.faint)
        Text(value.toString(), style = t.font.headline)
        Row(horizontalArrangement = Arrangement.spacedBy(t.space.xs)) {
            TinyButton("−") { if (value > 0) onChange(value - 1) }
            TinyButton("+") { if (value < 8) onChange(value + 1) }
        }
    }
}

@Composable
private fun TinyButton(label: String, onClick: () -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(t.radius.sm))
            .background(t.color.paper)
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = t.font.caption)
    }
}
