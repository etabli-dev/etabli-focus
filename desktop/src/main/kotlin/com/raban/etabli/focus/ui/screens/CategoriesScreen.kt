// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.FocusApp
import com.raban.etabli.focus.data.CategoryEntity
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
import com.raban.etabli.focus.ui.theme.PromptHeader
import com.raban.etabli.focus.ui.theme.SecondaryButton
import com.raban.etabli.focus.ui.theme.StepButton
import com.raban.etabli.focus.ui.theme.categoryColor
import com.raban.etabli.focus.ui.theme.pressable
import kotlinx.coroutines.launch
import java.util.UUID

// Same twelve swatches as the Android editor.
private val PaletteSwatches = listOf(
    "28A745", "D97706", "2563EB", "DC2626", "7C3AED", "0EA5E9",
    "B91C1C", "65A30D", "F59E0B", "EC4899", "475569", "059669",
)

@Composable
fun CategoriesScreen(app: FocusApp) {
    val t = Coder.tokens
    val scope = rememberCoroutineScope()
    val categories by remember { app.db.categoryDao().observeAll() }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.xl),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("categories", categories.size.toString()))
        Card(title = "all", icon = FocusIcons.Label) {
            if (categories.isEmpty()) {
                EmptyState("No categories yet — add your first one.")
            } else {
                Column {
                    categories.forEachIndexed { idx, cat ->
                        ListRow(
                            title = cat.name,
                            metadata = "${cat.defaultFocusMinutes} min · ${cat.defaultBreakMinutes} min break",
                            leading = { ColorDot(categoryColor(cat.colorHex)) },
                            onClick = { editing = cat },
                        )
                        if (idx < categories.lastIndex) HorizontalHairline()
                    }
                }
            }
        }
        MonoLabel("history keeps sessions of deleted categories.", color = t.color.faint)
        PrimaryButton("Add category", icon = Icons.Default.Add) { creating = true }
    }

    val draft = remember(creating) { if (creating) newCategory(categories) else null }
    val target = editing ?: draft
    target?.let { initial ->
        EditorDialog(
            initial = initial,
            isNew = editing == null,
            onDismiss = { editing = null; creating = false },
            onSave = { updated ->
                val isNew = editing == null
                scope.launch {
                    if (isNew) app.db.categoryDao().insert(updated) else app.db.categoryDao().update(updated)
                }
                editing = null; creating = false
            },
            onDelete = {
                editing?.let { ent -> scope.launch { app.db.categoryDao().delete(ent.id) } }
                editing = null; creating = false
            },
        )
    }
}

private fun newCategory(existing: List<CategoryEntity>) = CategoryEntity(
    id = UUID.randomUUID().toString(),
    name = "",
    colorHex = "28A745",
    defaultFocusMinutes = 25,
    defaultBreakMinutes = 5,
    order = (existing.maxOfOrNull { it.order } ?: -1) + 1,
)

@Composable
private fun EditorDialog(
    initial: CategoryEntity,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CategoryEntity) -> Unit,
    onDelete: () -> Unit,
) {
    val t = Coder.tokens
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var color by remember(initial.id) { mutableStateOf(initial.colorHex) }
    var focusMin by remember(initial.id) { mutableIntStateOf(initial.defaultFocusMinutes) }
    var breakMin by remember(initial.id) { mutableIntStateOf(initial.defaultBreakMinutes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.color.surface,
        title = { Text(if (isNew) "New category" else "Edit category", style = t.font.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.space.md)) {
                MonoLabel("name", color = t.color.faint)
                TextInput(value = name, placeholder = "e.g. Deep work", onChange = { name = it })
                MonoLabel("color", color = t.color.faint)
                PaletteSwatches.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                        row.forEach { hex -> Swatch(hex, selected = color == hex) { color = hex } }
                    }
                }
                MonoLabel("defaults", color = t.color.faint)
                MinutesRow("focus", focusMin, step = 5, range = 5..120) { focusMin = it }
                MinutesRow("break", breakMin, step = 1, range = 0..30) { breakMin = it }
                if (!isNew) {
                    DangerButton("Delete", icon = Icons.Default.Delete, onClick = onDelete)
                }
            }
        },
        confirmButton = {
            PrimaryButton(if (isNew) "Create" else "Save") {
                onSave(
                    initial.copy(
                        name = name.trim().ifBlank { initial.name.ifBlank { "Untitled" } },
                        colorHex = color,
                        defaultFocusMinutes = focusMin,
                        defaultBreakMinutes = breakMin,
                    ),
                )
            }
        },
        dismissButton = { SecondaryButton("Cancel", onDismiss) },
    )
}

@Composable
private fun MinutesRow(label: String, value: Int, step: Int, range: IntRange, onChange: (Int) -> Unit) {
    val t = Coder.tokens
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
        MonoLabel("$label  $value min")
        StepButton("−", "Shorter $label", enabled = value - step >= range.first) { onChange(value - step) }
        StepButton("+", "Longer $label", enabled = value + step <= range.last) { onChange(value + step) }
    }
}

@Composable
private fun TextInput(value: String, placeholder: String, onChange: (String) -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radius.sm))
            .background(t.color.paper)
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm))
            .padding(horizontal = t.space.md, vertical = t.space.sm),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = t.font.body.copy(color = t.color.faint))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = t.font.body.copy(color = t.color.ink),
            cursorBrush = SolidColor(t.color.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Category name" },
        )
    }
}

@Composable
private fun Swatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(t.radius.sm))
            .background(categoryColor(hex))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) t.color.ink else t.color.hairline,
                RoundedCornerShape(t.radius.sm),
            )
            .pressable(onClick = onClick)
            .semantics { contentDescription = "Colour #$hex" + if (selected) ", selected" else "" },
    )
}
