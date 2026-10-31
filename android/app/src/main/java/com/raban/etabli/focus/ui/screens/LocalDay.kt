package com.raban.etabli.focus.ui.screens

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

internal data class LocalDay(val date: LocalDate, val zone: ZoneId) {
    val startMillis: Long get() = date.atStartOfDay(zone).toInstant().toEpochMilli()
    companion object {
        fun current(): LocalDay = ZoneId.systemDefault().let { LocalDay(LocalDate.now(it), it) }
    }
}

/** Refresh while visible, including when an idle screen crosses midnight or a zone changes. */
@Composable
internal fun rememberLocalDay(): LocalDay {
    var day by remember { mutableStateOf(LocalDay.current()) }
    LaunchedEffect(Unit) {
        while (true) {
            day = LocalDay.current()
            delay(1000)
        }
    }
    return day
}
