package com.raban.etabli.focus.calendar

import kotlin.test.*
import java.time.*

class CalendarTest {
    private val start = Instant.parse("2026-11-08T00:00:00Z")
    private val end = Instant.parse("2026-11-15T00:00:00Z")
    private fun xml(ical: String, status: String = "200 OK") = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/calendar/event.ics</d:href><d:propstat><d:prop><c:calendar-data><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
$ical
END:VCALENDAR]]></c:calendar-data></d:prop><d:status>HTTP/1.1 $status</d:status></d:propstat></d:response></d:multistatus>"""
    private fun event(extra: String = "", date: String = "DTSTART:20261109T120000Z") = """BEGIN:VEVENT
UID:synthetic-meeting
$date
DURATION:PT1H
SUMMARY:Review\, results
LOCATION:Room 2
$extra
END:VEVENT"""
    @Test fun expandedEventsRetainTimesTitlesAndCancellation() {
        val items = CalDavClient.parse(xml(event()+"\n"+event("STATUS:CANCELLED", "DTSTART:20261110T120000Z")), start, end)
        assertEquals(1, items.size)
        assertEquals("Review, results", items.single().title)
        assertEquals(Instant.parse("2026-11-09T12:00:00Z").toEpochMilli(), items.single().startMillis)
        assertEquals(3600000L, items.single().endMillis - items.single().startMillis)
    }
    @Test fun distinctRecurringOccurrencesAreKept() {
        val items = CalDavClient.parse(xml(event("RECURRENCE-ID:20261109T120000Z")+"\n"+event("RECURRENCE-ID:20261110T120000Z", "DTSTART:20261110T130000Z")), start, end)
        assertEquals(2, items.size)
        assertNotEquals(items[0].id, items[1].id)
    }
    @Test fun unexpandedSeriesAndPartialFailureAreVisibleErrors() {
        assertFailsWith<IllegalArgumentException> { CalDavClient.parse(xml(event("RRULE:FREQ=DAILY")), start, end) }
        assertFailsWith<IllegalArgumentException> { CalDavClient.parse(xml(event(), "403 Forbidden"), start, end) }
    }
    @Test fun externalEntitiesAndWrongDocumentsAreRejected() {
        assertFailsWith<IllegalArgumentException> { CalDavClient.parse("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><a>&x;</a>", start, end) }
        assertFailsWith<IllegalArgumentException> { CalDavClient.parse("<html/>", start, end) }
    }
    @Test fun remindersHaveTwoDistinctTimesAndSkipPast() {
        val entry = CalendarEntry("x", "Meeting", start.toEpochMilli() + 600000, start.toEpochMilli() + 1200000, false)
        val values = reminders(listOf(entry), 5, start.toEpochMilli())
        assertEquals(listOf(5, 0), values.map { it.minutesBefore })
        assertEquals(entry.startMillis - 300000, values.first().atMillis)
        assertEquals(1, reminders(listOf(entry), 0, start.toEpochMilli()).size)
        assertTrue(reminders(listOf(entry), 5, entry.startMillis).isEmpty())
    }
    @Test fun allDayReminderUsesNineLocalTime() {
        val zone = ZoneId.of("Europe/Berlin")
        val at = LocalDate.of(2026, 11, 9).atStartOfDay(zone).toInstant().toEpochMilli()
        val entry = CalendarEntry("day", "Conference", at, at + 86400000, true)
        assertEquals(9, Instant.ofEpochMilli(entry.reminderStart(zone)).atZone(zone).hour)
    }
    @Test fun requestIsReadOnlyExpandedAndMultipleCalendarsAreMerged() {
        var requests = 0
        val client = CalDavClient { config, query ->
            requests++
            assertTrue(config.url.startsWith("https://example.test/"))
            assertTrue(query.contains("<c:expand")); assertTrue(query.contains("<c:time-range"))
            xml(event())
        }
        val result = client.entries(CalendarConnection("https://example.test/a/\nhttps://example.test/b/", "user", "secret"), start)
        assertEquals(2, requests)
        assertEquals(1, result.size)
        assertFailsWith<IllegalArgumentException> { client.entries(CalendarConnection("http://example.test/", "user", "secret"), start) }
        assertFailsWith<IllegalArgumentException> { client.entries(CalendarConnection("https://user:secret@example.test/", "user", "secret"), start) }
    }
}
