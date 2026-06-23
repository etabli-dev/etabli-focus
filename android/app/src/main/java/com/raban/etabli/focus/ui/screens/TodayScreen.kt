package com.raban.etabli.focus.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.EtabliFocusApplication
import com.raban.etabli.focus.data.CategoryEntity
import com.raban.etabli.focus.engine.TimerKind
import com.raban.etabli.focus.engine.TimerPhase
import com.raban.etabli.focus.engine.progress
import com.raban.etabli.focus.engine.remainingSeconds
import com.raban.etabli.focus.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun TodayScreen(app: EtabliFocusApplication) {
    val t = Coder.tokens
    val categories by app.db.categoryDao().observeAll().collectAsState(initial = emptyList())
    val templates by app.db.templateDao().observeAll().collectAsState(initial = emptyList())
    val todayStart = remember { Calendar.getInstance().startOfDayMillis() }
    val sessionsToday by app.db.sessionDao()
        .observeCompletedFocusSince(todayStart)
        .collectAsState(initial = emptyList())

    val state by app.engine.state.collectAsState()
    var sheetCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    val isWide = LocalConfiguration.current.screenWidthDp >= 700

    val plan = remember(categories, templates, sessionsToday) {
        val weekday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        categories.mapNotNull { cat ->
            val target = templates.firstOrNull {
                it.weekday == weekday && it.categoryID == cat.id
            }?.targetBlocks ?: 0
            val completed = sessionsToday.count { it.categoryID == cat.id }
            if (target == 0 && completed == 0) null else Triple(cat, target, completed)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        if (isWide) {
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.lg)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(t.space.lg)) {
                    TimerColumn(state.phase, app)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(t.space.lg)) {
                    PlanCard(plan)
                    StartBlockCard(categories) { sheetCategory = it }
                }
            }
        } else {
            TimerColumn(state.phase, app)
            PlanCard(plan)
            StartBlockCard(categories) { sheetCategory = it }
        }
    }

    sheetCategory?.let { cat ->
        StartTimerSheet(cat, onDismiss = { sheetCategory = null }) { minutes ->
            app.engine.start(cat.id, TimerKind.Focus, minutes)
            sheetCategory = null
        }
    }
}

@Composable
private fun TimerColumn(phase: TimerPhase, app: EtabliFocusApplication) {
    val t = Coder.tokens
    Column(verticalArrangement = Arrangement.spacedBy(t.space.lg)) {
        PromptHeader(listOf("today", formattedDate()))
        Text(
            when (phase) {
                TimerPhase.Idle    -> "Ready"
                TimerPhase.Running -> "Focusing"
                TimerPhase.Paused  -> "Paused"
            },
            style = t.font.display,
        )
        Card(title = "now", icon = Icons.Default.PlayArrow) {
            if (phase == TimerPhase.Idle) {
                MonoLabel("Pick a category below to start a focus block.", color = t.color.faint)
            } else {
                LiveTimerInline(app)
            }
        }
    }
}

@Composable
private fun PlanCard(plan: List<Triple<CategoryEntity, Int, Int>>) {
    val t = Coder.tokens
    Card(title = "today's plan", icon = Icons.Default.Checklist) {
        if (plan.isEmpty()) {
            MonoLabel(
                "no plan for today — open Template to set targets per category per weekday.",
                color = t.color.faint
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(t.space.md)) {
                plan.forEach { (cat, target, completed) -> PlanRow(cat, target, completed) }
            }
        }
    }
}

@Composable
private fun StartBlockCard(categories: List<CategoryEntity>, onPick: (CategoryEntity) -> Unit) {
    Card(title = "start a block", icon = Icons.Default.PlayArrow) {
        Column {
            categories.forEachIndexed { idx, cat ->
                ListRow(
                    title = cat.name,
                    metadata = "${cat.defaultFocusMinutes} min focus · ${cat.defaultBreakMinutes} min break",
                    leading = { ColorDot(cat.color()) },
                    onClick = { onPick(cat) },
                )
                if (idx < categories.lastIndex) HorizontalHairline()
            }
        }
    }
}

@Composable
private fun LiveTimerInline(app: EtabliFocusApplication) {
    val t = Coder.tokens
    val state by app.engine.state.collectAsState()
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 1Hz tick — refresh display + let engine.tick() finalise when end passes.
    LaunchedEffect(state.phase) {
        while (state.phase == TimerPhase.Running) {
            nowTick = System.currentTimeMillis()
            app.engine.tick()
            delay(1000)
        }
    }

    val remaining = state.remainingSeconds(nowTick)
    val progress = state.progress(nowTick)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.space.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            val strokePx = 12f
            Canvas(modifier = Modifier.fillMaxSize()) {
                val diameter = size.minDimension - strokePx
                val offset = (size.minDimension - diameter) / 2
                drawCircle(
                    color = t.color.hairline,
                    radius = diameter / 2,
                    style = Stroke(width = strokePx),
                )
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
                Text(timeString(remaining), style = t.font.display)
                MonoLabel(
                    if (state.phase == TimerPhase.Paused) "paused" else "running",
                    color = t.color.faint,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
            when (state.phase) {
                TimerPhase.Running -> PrimaryButton("Pause", icon = Icons.Default.Pause) { app.engine.pause() }
                TimerPhase.Paused  -> PrimaryButton("Resume", icon = Icons.Default.PlayArrow) { app.engine.resume() }
                else -> Unit
            }
            ResetButton { app.engine.cancel() }
        }
    }
}

@Composable
private fun ResetButton(onClick: () -> Unit) {
    val t = Coder.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space.xs),
        modifier = Modifier
            .clip(RoundedCornerShape(t.radius.sm))
            .border(1.dp, t.color.danger.copy(alpha = 0.5f), RoundedCornerShape(t.radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = t.space.md, vertical = t.space.sm),
    ) {
        Icon(Icons.Default.Stop, contentDescription = "Reset", tint = t.color.danger,
             modifier = Modifier.size(16.dp))
        Text("Reset", style = t.font.body.copy(color = t.color.danger))
    }
}

@Composable
private fun PlanRow(cat: CategoryEntity, target: Int, completed: Int) {
    val t = Coder.tokens
    val pct = if (target == 0) 0f else (completed.toFloat() / target).coerceAtMost(1f)
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
        ColorDot(cat.color())
        Column(modifier = Modifier.weight(1f)) {
            Text(cat.name, style = t.font.body, maxLines = 1)
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(t.color.hairline, RoundedCornerShape(3.dp))) {
                Box(modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(pct)
                    .background(cat.color(), RoundedCornerShape(3.dp)))
            }
        }
        MonoLabel("$completed / $target",
                  color = if (target > 0 && completed >= target) t.color.accent else t.color.faint)
    }
}

@Composable
internal fun ColorDot(color: Color) {
    Box(modifier = Modifier.size(12.dp).background(color, CircleShape))
}

@Composable
internal fun HorizontalHairline() {
    val t = Coder.tokens
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(t.color.hairline))
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StartTimerSheet(
    cat: CategoryEntity,
    onDismiss: () -> Unit,
    onStart: (minutes: Int) -> Unit,
) {
    val t = Coder.tokens
    val sheet = rememberModalBottomSheetState()
    var minutes by remember { mutableIntStateOf(cat.defaultFocusMinutes) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
                     containerColor = t.color.surface) {
        Column(modifier = Modifier.padding(t.space.lg),
               verticalArrangement = Arrangement.spacedBy(t.space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                ColorDot(cat.color())
                Text(cat.name, style = t.font.title)
            }
            MonoLabel("focus block", color = t.color.faint)
            Card(title = "duration", icon = Icons.Default.Schedule) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("$minutes min")
                    Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                        StepperButton("−") { if (minutes > 5) minutes -= 5 }
                        StepperButton("+") { if (minutes < 120) minutes += 5 }
                    }
                }
                MonoLabel("default: ${cat.defaultFocusMinutes} min", color = t.color.faint)
            }
            PrimaryButton("Start focus", icon = Icons.Default.PlayArrow) { onStart(minutes) }
        }
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(t.radius.sm))
            .background(t.color.paper)
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = t.font.headline)
    }
}

private fun timeString(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
private fun formattedDate(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

private fun Calendar.startOfDayMillis(): Long {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    return timeInMillis
}

internal fun CategoryEntity.color(): Color {
    val v = colorHex.toLongOrNull(16) ?: return Color(0xFF28A745)
    val r = ((v shr 16) and 0xFF) / 255f
    val g = ((v shr 8) and 0xFF) / 255f
    val b = (v and 0xFF) / 255f
    return Color(r, g, b)
}
