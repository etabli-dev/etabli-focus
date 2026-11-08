// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.focus.notifications

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.raban.etabli.focus.MainActivity
import com.raban.etabli.focus.calendar.CalendarReminder
import com.raban.etabli.focus.calendar.calendarTime

private const val CALENDAR_CHANNEL = "etabli.focus.calendar"
class CalendarNotifications(private val context: Context) {
    private val preferences = context.getSharedPreferences("calendar_alarms", Context.MODE_PRIVATE)
    private fun pending(key: String, title: String = "", text: String = ""): PendingIntent {
        val intent = Intent(context, CalendarReminderReceiver::class.java)
            .setData(Uri.parse("etabli-calendar:" + Uri.encode(key)))
            .putExtra("title", title).putExtra("text", text)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    @Synchronized fun cancelAll() {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        preferences.getStringSet("keys", emptySet()).orEmpty().forEach { key ->
            val old = pending(key)
            manager.cancel(old)
            old.cancel()
        }
        check(preferences.edit().clear().commit()) { "Could not clear calendar reminders." }
    }
    @Synchronized fun schedule(reminders: List<CalendarReminder>) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val retained = mutableSetOf<String>()
        val current = reminders.map { it.key }.toSet()
        val edit = preferences.edit()
        preferences.getStringSet("keys", emptySet()).orEmpty().forEach { key ->
            val at = preferences.getLong("at:$key", Long.MAX_VALUE)
            // Allow a due alarm to be delivered even if refresh crossed its deadline.
            if (key in current || at <= now && at > now - 86_400_000L) {
                retained.add(key)
            } else {
                val old = pending(key)
                manager.cancel(old)
                old.cancel()
                edit.remove("at:$key")
            }
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(NotificationChannel(CALENDAR_CHANNEL, "Calendar reminders", NotificationManager.IMPORTANCE_HIGH))
        reminders.forEach { reminder ->
            retained.add(reminder.key)
            edit.putLong("at:${reminder.key}", reminder.atMillis)
            val text = (if (reminder.minutesBefore > 0) "In ${reminder.minutesBefore} minutes · " else "Starting now · ") + calendarTime(reminder.entry)
            val intent = pending(reminder.key, reminder.entry.title, text)
            try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMillis, intent) }
            catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMillis, intent) }
        }
        check(edit.putStringSet("keys", retained).commit()) { "Could not retain calendar reminder state." }
    }
}
class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("open_calendar", true)
        val pending = PendingIntent.getActivity(context, 8432, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CALENDAR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(intent.getStringExtra("title") ?: "Calendar appointment")
            .setContentText(intent.getStringExtra("text") ?: "View your calendar")
            .setContentIntent(pending).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_my_calendar, "View calendar", pending).build()
        try { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(intent.dataString.orEmpty().hashCode(), notification) }
        catch (_: SecurityException) { /* Calendar stays visible in the app when notifications are denied. */ }
    }
}
