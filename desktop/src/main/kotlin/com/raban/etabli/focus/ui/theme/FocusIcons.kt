// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.theme

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few glyphs not in material-icons-core, as 24-unit Material-style
 * vectors. Keeps the extended icon set (tens of MB) out of the installers.
 */
object FocusIcons {
    val Pause: ImageVector = icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val Stop: ImageVector = icon("Stop", "M6 6h12v12H6z")
    val BarChart: ImageVector = icon("BarChart", "M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z")
    val Label: ImageVector = icon(
        "Label",
        "M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 1.1.9 1.99 2 1.99L16 19" +
            "c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16z",
    )

    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}

/**
 * The suite icon from design/icon-focus.svg (white timer glyph on the accent
 * tile), drawn natively so the window and tray need no image files. With a
 * [progress] the elapsed part of the block fills the dial, which is what the
 * tray shows while a block runs.
 */
class FocusMarkPainter(private val progress: Float? = null) : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val unit = size.minDimension / 1024f
        scale(unit, unit, pivot = Offset.Zero) {
            drawRoundRect(
                color = CoderColors.AccentLight,
                size = Size(1024f, 1024f),
                cornerRadius = CornerRadius(225f, 225f),
            )
            val white = Color.White
            val stroke = Stroke(width = 36f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            progress?.let { fraction ->
                drawArc(
                    color = white.copy(alpha = 0.55f),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = true,
                    topLeft = Offset(512f - 180f, 556f - 180f),
                    size = Size(360f, 360f),
                )
            }
            drawCircle(white, radius = 216f, center = Offset(512f, 556f), style = stroke)
            drawPath(
                Path().apply {
                    moveTo(482f, 340f); lineTo(482f, 304f); lineTo(542f, 304f); lineTo(542f, 340f)
                },
                color = white, style = stroke,
            )
            drawLine(white, Offset(512f, 340f), Offset(512f, 380f), strokeWidth = 36f, cap = StrokeCap.Round)
            drawLine(white, Offset(512f, 556f), Offset(600f, 498f), strokeWidth = 36f, cap = StrokeCap.Round)
        }
    }

    override fun equals(other: Any?) = other is FocusMarkPainter && other.progress == progress
    override fun hashCode() = progress.hashCode()
}
