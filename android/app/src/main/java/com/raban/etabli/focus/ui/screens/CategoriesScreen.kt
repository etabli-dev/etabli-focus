// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.EtabliFocusApplication
import com.raban.etabli.focus.data.CategoryEntity
import com.raban.etabli.focus.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

private val PaletteSwatches = listOf(
    "28A745", "D97706", "2563EB", "DC2626", "7C3AED", "0EA5E9",
    "B91C1C", "65A30D", "F59E0B", "EC4899", "475569", "059669",
)

@Composable
fun CategoriesScreen(app: EtabliFocusApplication) {
    val t = Coder.tokens
    val scope = rememberCoroutineScope()
    val categories by app.db.categoryDao().observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("categories", categories.size.toString()))
        Card(title = "all", icon = Icons.Default.Category) {
            if (categories.isEmpty()) {
                EmptyState("No categories yet — add your first one.")
            } else {
                Column {
                    categories.forEachIndexed { idx, cat ->
                        ListRow(
                            title = cat.name,
                            metadata = "${cat.defaultFocusMinutes} min · ${cat.defaultBreakMinutes} min break",
                            leading = { ColorDot(cat.color()) },
                            onClick = { editing = cat },
                        )
                        if (idx < categories.lastIndex) HorizontalHairline()
                    }
                }
            }
        }
        PrimaryButton("Add category", icon = Icons.Default.Add) { creating = true }
    }

    val target = editing ?: if (creating) emptyCategory() else null
    target?.let { initial ->
        EditorSheet(
            initial = initial,
            isNew = editing == null,
            onDismiss = { editing = null; creating = false },
            onSave = { updated ->
                scope.launch {
                    if (editing == null) app.db.categoryDao().insert(updated)
                    else app.db.categoryDao().update(updated)
                }
                editing = null; creating = false
            },
            onDelete = {
                editing?.let { ent ->
                    scope.launch { app.db.categoryDao().delete(ent.id) }
                }
                editing = null; creating = false
            },
        )
    }
}

private fun emptyCategory() = CategoryEntity(
    id = UUID.randomUUID().toString(),
    name = "",
    colorHex = "28A745",
    defaultFocusMinutes = 25,
    defaultBreakMinutes = 5,
    order = Int.MAX_VALUE,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(
    initial: CategoryEntity,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CategoryEntity) -> Unit,
    onDelete: () -> Unit,
) {
    val t = Coder.tokens
    val sheet = rememberModalBottomSheetState()
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var color by remember(initial.id) { mutableStateOf(initial.colorHex) }
    var focusMin by remember(initial.id) { mutableIntStateOf(initial.defaultFocusMinutes) }
    var breakMin by remember(initial.id) { mutableIntStateOf(initial.defaultBreakMinutes) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
                     containerColor = t.color.surface) {
        Column(modifier = Modifier.padding(t.space.lg).fillMaxWidth(),
               verticalArrangement = Arrangement.spacedBy(t.space.lg)) {
            Text(if (isNew) "New category" else "Edit category", style = t.font.title)

            Card(title = "name") {
                TextInput(value = name, placeholder = "e.g. Deep work", onChange = { name = it })
            }
            Card(title = "color") {
                FlowSwatches(selected = color, onPick = { color = it })
            }
            Card(title = "defaults") {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("focus  $focusMin min")
                    Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                        TinyStep("−") { if (focusMin > 5) focusMin -= 5 }
                        TinyStep("+") { if (focusMin < 120) focusMin += 5 }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("break  $breakMin min")
                    Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                        TinyStep("−") { if (breakMin > 0) breakMin -= 1 }
                        TinyStep("+") { if (breakMin < 30) breakMin += 1 }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
                PrimaryButton(if (isNew) "Create" else "Save") {
                    onSave(
                        initial.copy(
                            name = name.ifBlank { initial.name.ifBlank { "Untitled" } },
                            colorHex = color,
                            defaultFocusMinutes = focusMin,
                            defaultBreakMinutes = breakMin,
                        )
                    )
                }
                if (!isNew) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(t.space.xs),
                        modifier = Modifier
                            .clip(RoundedCornerShape(t.radius.sm))
                            .border(1.dp, t.color.danger.copy(alpha = 0.5f),
                                    RoundedCornerShape(t.radius.sm))
                            .clickable(onClick = onDelete)
                            .padding(horizontal = t.space.md, vertical = t.space.sm),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete",
                             tint = t.color.danger, modifier = Modifier.size(16.dp))
                        Text("Delete", style = t.font.body.copy(color = t.color.danger))
                    }
                }
            }
        }
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
            textStyle = t.font.body.copy(color = t.color.ink),
            cursorBrush = SolidColor(t.color.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FlowSwatches(selected: String, onPick: (String) -> Unit) {
    val t = Coder.tokens
    Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm),
        modifier = Modifier.fillMaxWidth()) {
        // Two rows of six.
        Column(verticalArrangement = Arrangement.spacedBy(t.space.sm),
               modifier = Modifier.weight(1f)) {
            PaletteSwatches.take(6).chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                    row.forEach { hex -> Swatch(hex, selected == hex) { onPick(hex) } }
                }
            }
            PaletteSwatches.drop(6).chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                    row.forEach { hex -> Swatch(hex, selected == hex) { onPick(hex) } }
                }
            }
        }
    }
}

@Composable
private fun Swatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val t = Coder.tokens
    val color = CategoryEntity(
        id = "", name = "", colorHex = hex,
        defaultFocusMinutes = 0, defaultBreakMinutes = 0, order = 0,
    ).color()
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(t.radius.sm))
            .background(color)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) t.color.ink else t.color.hairline,
                RoundedCornerShape(t.radius.sm),
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun TinyStep(label: String, onClick: () -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(t.radius.sm))
            .background(t.color.paper)
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = t.font.caption) }
}
