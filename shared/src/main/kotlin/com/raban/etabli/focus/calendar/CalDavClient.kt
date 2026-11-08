// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.focus.calendar

import biweekly.Biweekly
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Read-only calendar collection, not a server/account discovery URL. */
data class CalendarConnection(val url: String, val username: String, val password: String, val leadMinutes: Int = 5) {
    fun validate() {
        val urls = url.lines().filter { it.isNotBlank() }
        require(urls.isNotEmpty() && urls.size <= 20) { "Enter between one and twenty calendar collection URLs." }
        urls.forEach { address ->
        val uri = URI(address.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null) {
            "Use an HTTPS CalDAV calendar collection URL without embedded credentials."
        }
        }
        require(username.isNotBlank() && !username.contains(':') && !username.contains('\n') && password.isNotEmpty()) {
            "Enter the calendar username and app password."
        }
        require(leadMinutes in 0..60) { "Reminder lead time must be between 0 and 60 minutes." }
    }
}

data class CalendarEntry(
    val id: String, val title: String, val startMillis: Long, val endMillis: Long,
    val allDay: Boolean, val location: String = "", val description: String = "",
) {
    fun reminderStart(zone: ZoneId = ZoneId.systemDefault()): Long = if (allDay)
        Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate().atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        else startMillis
}

fun interface CalendarTransport { fun report(connection: CalendarConnection, xml: String): String }

class HttpsCalendarTransport : CalendarTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build()
    override fun report(connection: CalendarConnection, xml: String): String {
        connection.validate()
        val request = Request.Builder().url(connection.url).header("Depth", "1")
            .header("Authorization", Credentials.basic(connection.username, connection.password, Charsets.UTF_8))
            .header("Accept", "application/xml, text/xml")
            .method("REPORT", xml.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            when (response.code) {
                401, 403 -> error("Calendar access denied. Check the username, app password and calendar permissions.")
                301, 302, 307, 308 -> error("The calendar URL redirects. Enter the final HTTPS collection URL; credentials were not forwarded.")
                207 -> Unit
                else -> error("Calendar sync failed (HTTP ${response.code}). Check the CalDAV collection URL.")
            }
            val source = response.body?.source() ?: error("The calendar server returned an empty response.")
            val limit = 8L * 1024 * 1024
            if (source.request(limit + 1)) error("Calendar response exceeds 8 MiB. Select a smaller calendar.")
            source.readUtf8()
        }
    }
}

/** RFC 4791 server expansion handles RRULE, EXDATE and modified occurrences. */
class CalDavClient(private val transport: CalendarTransport = HttpsCalendarTransport()) {
    fun entries(connection: CalendarConnection, now: Instant = Instant.now()): List<CalendarEntry> {
        connection.validate()
        val urls = connection.url.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.size > 1) return urls.flatMap { entries(connection.copy(url = it), now) }.distinctBy { it.id }.sortedBy { it.startMillis }
        val start = now.minusSeconds(86400)
        val end = now.plusSeconds(7 * 86400)
        val format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val range = "start=\"${format.format(start)}\" end=\"${format.format(end)}\""
        val query = """<?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
            <d:prop><d:getetag/><c:calendar-data><c:expand $range/></c:calendar-data></d:prop>
            <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">
            <c:time-range $range/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>""".trimIndent()
        return parse(transport.report(connection, query), start, end)
    }

    companion object {
        fun parse(xml: String, start: Instant, end: Instant): List<CalendarEntry> {
            require(xml.length <= 8 * 1024 * 1024) { "Calendar response is too large." }
            require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "Unsupported calendar XML declarations." }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                // Android and desktop expose different XML features; the lexical
                // declaration rejection and resolver also apply on both runtimes.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External calendar XML entities are not allowed.") }
            val doc = builder.parse(InputSource(StringReader(xml)))
            require(doc.documentElement.localName == "multistatus" && doc.documentElement.namespaceURI == "DAV:") {
                "The server did not return a CalDAV calendar response."
            }
            val results = mutableListOf<CalendarEntry>()
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            for (i in 0 until responses.length) {
                val response = responses.item(i) as Element
                val statuses = response.getElementsByTagNameNS("DAV:", "status")
                val data = response.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")
                if (data.length == 0) {
                    if ((0 until statuses.length).any { !statuses.item(it).textContent.contains(" 200 ") })
                        error("The server could not read every calendar entry. Refresh after checking calendar access.")
                    continue
                }
                for (j in 0 until data.length) {
                    val property = data.item(j).parentNode.parentNode as? Element
                    val propertyStatus = property?.getElementsByTagNameNS("DAV:", "status")
                    require(propertyStatus != null && propertyStatus.length > 0 && propertyStatus.item(0).textContent.contains(" 200 ")) {
                        "The server could not read every calendar entry."
                    }
                    val icals = Biweekly.parse(data.item(j).textContent).all()
                    for (ical in icals) for (event in ical.events) {
                        if (event.status?.value.equals("CANCELLED", true)) continue
                        require(event.recurrenceRule == null && event.recurrenceDates.isEmpty()) {
                            "This server did not expand recurring events. Use a CalDAV server supporting calendar-data expand."
                        }
                        val dt = event.dateStart?.value ?: error("A calendar entry has no start date.")
                        val allDay = !dt.hasTime()
                        val begin = dt.time
                        val finish = event.dateEnd?.value?.time ?: event.duration?.value?.let {
                            java.util.Date(begin).let { date -> it.add(date).time }
                        } ?: if (allDay) Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).plusDays(1).toInstant().toEpochMilli() else begin
                        if (finish < start.toEpochMilli() || begin >= end.toEpochMilli()) continue
                        val uid = event.uid?.value ?: error("A calendar entry has no stable identifier.")
                        results += CalendarEntry("$uid:$begin", event.summary?.value ?: "Untitled event", begin, finish,
                            allDay, event.location?.value.orEmpty(), event.description?.value.orEmpty())
                    }
                }
            }
            require(results.size <= 10000) { "Calendar contains too many entries for this date range." }
            return results.distinctBy { it.id }.sortedBy { it.startMillis }
        }
    }
}
