// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

internal data class LocalDay(val date: LocalDate, val zone: ZoneId) {
    val startMillis: Long get() = date.atStartOfDay(zone).toInstant().toEpochMilli()
    companion object {
        fun current(): LocalDay = ZoneId.systemDefault().let { LocalDay(LocalDate.now(it), it) }
    }
}

/** Refreshes while visible, including when an idle window crosses midnight or the zone changes. */
@Composable
internal fun rememberLocalDay(): LocalDay = produceState(LocalDay.current()) {
    while (true) {
        value = LocalDay.current()
        delay(1000)
    }
}.value

/** Wall-clock milliseconds for countdown displays, refreshed a few times per second. */
@Composable
internal fun rememberNow(periodMillis: Long = 250): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(periodMillis)
    }
}

internal fun clockText(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
