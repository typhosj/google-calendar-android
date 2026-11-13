package io.github.typhosj.familycalendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

const val WINDOW_DAYS = 60L

/** Known limit: entries more than two years ahead are not listed. */
const val HORIZON_DAYS = 730L

private val DAY_TEXT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val SHORT_DAY = DateTimeFormatter.ofPattern("dd.MM.")
private val TIME_TEXT = DateTimeFormatter.ofPattern("HH:mm")

/** One occurrence from the calendar provider. All-day values are UTC midnights, the end exclusive. */
data class Instance(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val calendarName: String,
    val calendarId: Long = 0,
)

enum class Tone { NOW, NEXT, PLAIN }

/** One list row, with the texts of the adapter's `upcoming` list. */
data class AgendaItem(
    val key: String,
    val day: String,
    val title: String,
    val right: String,
    val type: EntryType,
    val tone: Tone,
    val instance: Instance,
)

/** Rows of the loaded windows, and the day to go on from, or null at the horizon. */
data class Page(val items: List<AgendaItem>, val next: LocalDate?)

private fun at(millis: Long, zone: ZoneId): LocalDateTime =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

private fun Instance.start(zone: ZoneId): LocalDateTime = at(begin, if (allDay) ZoneOffset.UTC else zone)

private fun Instance.finish(zone: ZoneId): LocalDateTime = at(end, if (allDay) ZoneOffset.UTC else zone)

private fun Instance.isOver(now: LocalDateTime, zone: ZoneId): Boolean =
    if (allDay) finish(zone).toLocalDate() <= now.toLocalDate() else finish(zone) <= now

private fun toItem(instance: Instance, now: LocalDateTime, zone: ZoneId): AgendaItem {
    val today = now.toLocalDate()
    val start = instance.start(zone)
    val end = instance.finish(zone)
    val last = if (instance.allDay) end.minusDays(1) else end
    val multiDay = last.toLocalDate() > start.toLocalDate()
    val type = EntryType.entries.find { it.calendarName == instance.calendarName } ?: EntryType.APPOINTMENT
    val (day, tone) = when {
        multiDay && start.toLocalDate() < today -> "läuft" to Tone.NOW
        start.toLocalDate() == today -> "Heute" to Tone.NOW
        start.toLocalDate() == today.plusDays(1) -> "Morgen" to Tone.NEXT
        else -> start.format(DAY_TEXT) to Tone.PLAIN
    }
    val right = when {
        type == EntryType.BIRTHDAY -> ""
        instance.allDay -> if (multiDay) "bis ${last.format(SHORT_DAY)}" else ""
        multiDay -> "bis ${end.format(SHORT_DAY)} ${end.format(TIME_TEXT)}"
        start == end -> start.format(TIME_TEXT)
        else -> "${start.format(TIME_TEXT)} - ${end.format(TIME_TEXT)}"
    }
    return AgendaItem(
        key = "${instance.eventId}-${instance.begin}",
        day = day,
        title = instance.title.trim().ifEmpty { "(ohne Titel)" },
        right = right,
        type = type,
        tone = tone,
        instance = instance,
    )
}

/**
 * Loads whole windows from [from] on until [count] entries are found or the horizon is reached.
 * Each entry belongs to the window of its local start day, so it is listed once; only the window
 * starting today also takes entries that started earlier and are still running.
 * [fetch] returns the occurrences overlapping the days from its first argument up to its second.
 */
fun loadPage(
    fetch: (LocalDate, LocalDate) -> List<Instance>,
    from: LocalDate,
    count: Int,
    now: LocalDateTime,
    zone: ZoneId,
): Page {
    val today = now.toLocalDate()
    val horizon = today.plusDays(HORIZON_DAYS)
    val found = mutableListOf<Instance>()
    var start = from
    while (found.size < count && start < horizon) {
        val windowEnd = start.plusDays(WINDOW_DAYS)
        val end = if (windowEnd < horizon) windowEnd else horizon
        found += fetch(start, end).filter {
            val day = it.start(zone).toLocalDate()
            day < end && (day >= start || start == today) && !it.isOver(now, zone)
        }
        start = end
    }
    val sorted = found.sortedWith(compareBy({ it.start(zone) }, { it.title }))
    return Page(sorted.map { toItem(it, now, zone) }.distinctBy { it.key }, start.takeIf { it < horizon })
}
