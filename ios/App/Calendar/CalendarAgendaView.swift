// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import EventKit

struct CalendarAgendaView: View {
    @Environment(CalendarAgenda.self) private var agenda
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        @Bindable var agenda = agenda
        NavigationStack {
            Form {
                Section("Calendar accounts") {
                    Text("Shows calendars already synced with iOS, including CalDAV. Add your CalDAV account in iOS Settings → Apps → Calendar → Calendar Accounts. Établi Focus only reads events.")
                    if !agenda.authorized {
                        Button("Allow calendar access") { Task { await agenda.requestAccess() } }
                        Link("Open Settings", destination: URL(string: UIApplication.openSettingsURLString)!)
                    } else {
                        ForEach(agenda.calendars, id: \.calendarIdentifier) { calendar in
                            Toggle(calendar.title + " · " + calendar.source.title, isOn: Binding(
                                get: { agenda.selectedIDs.contains(calendar.calendarIdentifier) },
                                set: { enabled in
                                    if enabled { agenda.selectedIDs.insert(calendar.calendarIdentifier) }
                                    else { agenda.selectedIDs.remove(calendar.calendarIdentifier) }
                                }))
                        }
                    }
                    if let message = agenda.message { Text(message).foregroundStyle(.secondary) }
                }
                Section("Reminders") {
                    Toggle("Remind me about calendar events", isOn: $agenda.remindersEnabled)
                    Stepper("\(agenda.leadMinutes) minutes before", value: $agenda.leadMinutes, in: 0...60, step: 5)
                    Text("Timed events notify before the event and at its start. All-day events remind you at 09:00 local time and beforehand. Reminders never stop your Pomodoro.").font(.footnote)
                    Text("Changes sync through iOS. Open Établi Focus after editing events elsewhere to refresh reminders; system background delivery and notification settings apply.").font(.footnote)
                    Button("Refresh calendar") { Task { await agenda.refresh() } }
                }
                Section("Next 48 hours") { CalendarEventRows() }
            }
            .navigationTitle("Calendar")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .task { await agenda.refresh() }
        }
    }
}

struct CalendarEventRows: View {
    @Environment(CalendarAgenda.self) private var agenda
    var limit: Int = 50
    var body: some View {
        if agenda.appointments.isEmpty { Text("No upcoming events in the selected calendars.").foregroundStyle(.secondary) }
        ForEach(Array(agenda.appointments.prefix(limit))) { event in
            Link(destination: URL(string: "calshow:\(event.start.timeIntervalSinceReferenceDate)")!) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(event.title).font(.headline)
                    Text(event.allDay ? "All day · \(event.start.formatted(date: .abbreviated, time: .omitted))" : event.start.formatted(date: .abbreviated, time: .shortened))
                    Text(event.calendarTitle).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}
