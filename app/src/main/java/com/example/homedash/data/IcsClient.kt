package com.example.homedash.data

import biweekly.Biweekly
import biweekly.component.VEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class IcsEvent(val date: LocalDate, val title: String, val start: LocalTime?, val time: String)

private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

class IcsClient {
    /** Events from one secret ICS feed that start between [first] and [last] inclusive, recurrences expanded. */
    suspend fun eventsBetween(url: String, first: LocalDate, last: LocalDate): List<IcsEvent> = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val zone = ZoneId.systemDefault()
            val windowStart = Date.from(first.atStartOfDay(zone).toInstant())
            val windowEnd = last.plusDays(1).atStartOfDay(zone).toInstant()
            conn.inputStream.use { stream ->
                Biweekly.parse(stream).first()?.events.orEmpty().flatMap { event ->
                    runCatching { occurrences(event, zone, windowStart, windowEnd) }.getOrDefault(emptyList())
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun occurrences(event: VEvent, zone: ZoneId, windowStart: Date, windowEnd: Instant): List<IcsEvent> {
        val title = event.summary?.value ?: "(no title)"
        val allDay = event.dateStart?.value?.hasTime() == false
        val iterator = event.getDateIterator(TimeZone.getDefault())
        iterator.advanceTo(windowStart)
        val out = mutableListOf<IcsEvent>()
        while (iterator.hasNext()) {
            val start = iterator.next().toInstant().atZone(zone)
            if (start.toInstant() >= windowEnd) break
            out += IcsEvent(
                date = start.toLocalDate(),
                title = title,
                start = if (allDay) null else start.toLocalTime(),
                time = if (allDay) "All day" else start.format(TIME_FORMAT),
            )
        }
        return out
    }
}
