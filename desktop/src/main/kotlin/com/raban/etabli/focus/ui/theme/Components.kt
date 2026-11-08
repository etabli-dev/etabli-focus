// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Shared styled components, ported from the Android ui/theme/Components.kt
// (itself the twin of the iOS Theme.swift components). Desktop additions: a
// hand cursor on clickable elements.

fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)

@Composable
fun PromptHeader(
    segments: List<String>,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = Coder.tokens
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("~/", style = t.font.monoBody.copy(color = t.color.accent))
        segments.forEachIndexed { idx, seg ->
            if (idx > 0) Text("/", style = t.font.monoBody.copy(color = t.color.faint))
            Text(seg, style = t.font.monoBody.copy(color = t.color.ink))
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun MonoLabel(text: String, color: Color? = null) {
    val t = Coder.tokens
    Text(text, style = t.font.mono.copy(color = color ?: t.color.ink))
}

@Composable
fun Card(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = Coder.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(t.color.surface, RoundedCornerShape(t.radius.md))
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.md))
            .padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = t.color.accent,
                     modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(t.space.xs))
            }
            Text(title, style = t.font.headline)
        }
        content()
    }
}

@Composable
fun PrimaryButton(
    title: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val t = Coder.tokens
    val bg = if (enabled) t.color.accent else t.color.faint
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space.sm),
        modifier = Modifier
            .clip(RoundedCornerShape(t.radius.sm))
            .background(bg)
            .pressable(enabled, onClick)
            .padding(horizontal = t.space.md, vertical = t.space.sm),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = t.color.surface,
                               modifier = Modifier.size(16.dp))
        Text(title, style = t.font.body.copy(color = t.color.surface))
    }
}

/** Outlined button in the danger tone (Reset / Delete). */
@Composable
fun DangerButton(title: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val t = Coder.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space.xs),
        modifier = Modifier
            .clip(RoundedCornerShape(t.radius.sm))
            .border(1.dp, t.color.danger.copy(alpha = 0.5f), RoundedCornerShape(t.radius.sm))
            .pressable(onClick = onClick)
            .padding(horizontal = t.space.md, vertical = t.space.sm),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = t.color.danger,
                               modifier = Modifier.size(16.dp))
        Text(title, style = t.font.body.copy(color = t.color.danger))
    }
}

/** Outlined neutral button (secondary actions such as Cancel). */
@Composable
fun SecondaryButton(title: String, onClick: () -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(t.radius.sm))
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm))
            .pressable(onClick = onClick)
            .padding(horizontal = t.space.md, vertical = t.space.sm),
    ) {
        Text(title, style = t.font.body.copy(color = t.color.ink))
    }
}

@Composable
fun ListRow(
    title: String,
    metadata: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val t = Coder.tokens
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(t.space.md),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(vertical = t.space.sm),
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            leading?.invoke()
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = t.font.body, maxLines = 1)
            if (metadata != null) {
                Text(metadata, style = t.font.mono.copy(color = t.color.faint), maxLines = 1)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(title: String, detail: String? = null) {
    val t = Coder.tokens
    Column(
        modifier = Modifier.fillMaxWidth().padding(t.space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = t.font.headline)
        if (detail != null) {
            Spacer(Modifier.height(t.space.xs))
            Text(detail, style = t.font.mono.copy(color = t.color.faint),
                 textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ColorDot(color: Color) {
    Box(modifier = Modifier.size(12.dp).background(color, CircleShape))
}

@Composable
fun HorizontalHairline() {
    val t = Coder.tokens
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(t.color.hairline))
}

/** Small square stepper control ("−" / "+") with an accessible description. */
@Composable
fun StepButton(label: String, description: String, enabled: Boolean = true, size: Int = 32, onClick: () -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(t.radius.sm))
            .background(t.color.paper)
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm))
            .pressable(enabled, onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = t.font.headline.copy(color = if (enabled) t.color.ink else t.color.faint),
             modifier = Modifier.clearAndSetSemantics { })
    }
}

/** Selectable chip (Stats window, theme choice). */
@Composable
fun SegmentChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(t.radius.sm))
            .background(if (selected) t.color.accent.copy(alpha = 0.12f) else t.color.surface)
            .border(
                1.dp,
                if (selected) t.color.accent else t.color.hairline,
                RoundedCornerShape(t.radius.sm),
            )
            .pressable(onClick = onClick)
            .padding(horizontal = t.space.md, vertical = t.space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = t.font.caption.copy(color = if (selected) t.color.accent else t.color.faint))
    }
}

/** Horizontal progress bar in a category colour. */
@Composable
fun ProgressBar(fraction: Float, color: Color) {
    val t = Coder.tokens
    Box(modifier = Modifier
        .fillMaxWidth()
        .height(6.dp)
        .background(t.color.hairline, RoundedCornerShape(3.dp))) {
        Box(modifier = Modifier
            .height(6.dp)
            .fillMaxWidth(fraction.coerceIn(0f, 1f))
            .background(color, RoundedCornerShape(3.dp)))
    }
}

/** Parses a stored "RRGGBB" category colour, falling back to the accent. */
fun categoryColor(hex: String): Color {
    val v = hex.toLongOrNull(16) ?: return CoderColors.AccentLight
    val r = ((v shr 16) and 0xFF) / 255f
    val g = ((v shr 8) and 0xFF) / 255f
    val b = (v and 0xFF) / 255f
    return Color(r, g, b)
}
