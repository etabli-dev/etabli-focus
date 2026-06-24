// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.raban.etabli.focus.R
import com.raban.etabli.focus.engine.TimerKind

// Android twin of iOS's UNUserNotificationCenter scheduling. Posts a single
// notification at the end-time of the active focus/break block via
// AlarmManager + a BroadcastReceiver. Channel is created idempotently.

private const val CHANNEL_ID = "etabli.focus.completion"
private const val REQUEST_CODE = 4321
internal const val EXTRA_KIND = "kind"

class NotificationScheduler(private val context: Context) {

    init { ensureChannel() }

    fun scheduleCompletion(at: Long, kind: TimerKind) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(kind)
        // Cancel any prior scheduled completion.
        am.cancel(pending)
        // On Android 12+ exact alarms require either user grant OR a privileged
        // use case. We try exact first, fall back to inexact on SecurityException.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(TimerKind.Focus))
        am.cancel(pendingIntent(TimerKind.ShortBreak))
        am.cancel(pendingIntent(TimerKind.LongBreak))
    }

    private fun pendingIntent(kind: TimerKind): PendingIntent {
        val intent = Intent(context, TimerCompletionReceiver::class.java).apply {
            putExtra(EXTRA_KIND, kind.name)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE + kind.ordinal, intent, flags)
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        nm.createNotificationChannel(channel)
    }
}

class TimerCompletionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kindName = intent.getStringExtra(EXTRA_KIND) ?: TimerKind.Focus.name
        val kind = runCatching { TimerKind.valueOf(kindName) }.getOrDefault(TimerKind.Focus)

        // Refuse to crash if the runtime POST_NOTIFICATIONS permission was
        // never granted — just don't post.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val title = if (kind == TimerKind.Focus) "Focus block complete" else "Break complete"
        val body = if (kind == TimerKind.Focus)
            "Suggest a short break — or start the next block."
        else
            "Time to get back to it when you're ready."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1001, builder.build())
    }
}
