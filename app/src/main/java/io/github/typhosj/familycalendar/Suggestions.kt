package io.github.typhosj.familycalendar

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** How far back and ahead earlier titles are read. */
const val SUGGEST_DAYS = 365L
private const val MIN_CHARS = 2
private const val LIMIT = 4
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/**
 * One earlier title with the values of its latest occurrence: [minutes] for a timed entry,
 * [days] for an all-day one; [count] occurrences in the window, [latest] the latest start.
 */
data class Suggestion(
    val title: String,
    val type: EntryType,
    val allDay: Boolean,
    val startTime: LocalTime,
    val minutes: Long,
    val days: Long,
    val count: Int,
    val latest: Long,
)

private fun key(text: String) = collapse(text).lowercase()

/**
 * One suggestion per title of the calendars in [typeOf], most frequent first, then the latest start.
 * A series counts once per occurrence, so a weekly entry ranks high.
 */
fun suggestions(instances: List<Instance>, typeOf: Map<Long, EntryType>, zone: ZoneId): List<Suggestion> =
    instances
        .filter { it.calendarId in typeOf && key(it.title).isNotEmpty() }
        .groupBy { key(it.title) }
        .values
        .map { group ->
            val last = group.maxBy { it.begin }
            val clockZone = if (last.allDay) ZoneOffset.UTC else zone
            val start = Instant.ofEpochMilli(last.begin).atZone(clockZone).toLocalDateTime()
            val end = Instant.ofEpochMilli(last.end).atZone(clockZone).toLocalDateTime()
            Suggestion(
                title = collapse(last.title),
                type = typeOf.getValue(last.calendarId),
                allDay = last.allDay,
                startTime = start.toLocalTime(),
                minutes = if (last.allDay) 0 else Duration.between(start, end).toMinutes().coerceAtLeast(0),
                days = if (last.allDay) ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()).coerceAtLeast(1) else 0,
                count = group.size,
                latest = last.begin,
            )
        }
        .sortedWith(compareByDescending<Suggestion> { it.count }.thenByDescending { it.latest })

private fun startsWord(title: String, typed: String) =
    title.indices.any { (it == 0 || !title[it - 1].isLetterOrDigit()) && title.startsWith(typed, it) }

/** Up to four suggestions whose title or one of its words starts with [text]; none on an exact title. */
fun matching(text: String, all: List<Suggestion>): List<Suggestion> {
    val typed = key(text)
    if (typed.length < MIN_CHARS || all.any { key(it.title) == typed }) return emptyList()
    return all.filter { startsWord(key(it.title), typed) }.take(LIMIT)
}

/** A timed appointment's length on pick; a zero-length one gets an hour, as a reminder's hidden end. */
private fun Suggestion.length() = if (type == EntryType.APPOINTMENT && !allDay && minutes > 0) minutes else 60

/**
 * The form after a pick: text, type and all-day from [suggestion], the chosen date kept. A timed
 * pick takes the start time; an all-day pick keeps the form's times and ends after its days.
 */
fun applySuggestion(form: Form, suggestion: Suggestion): Form {
    val allDay = suggestion.type == EntryType.APPOINTMENT && suggestion.allDay
    val time = if (suggestion.allDay) form.startTime else suggestion.startTime
    val end = form.startDate.atTime(time).plusMinutes(suggestion.length())
    val picked = form.copy(
        text = suggestion.title,
        type = suggestion.type,
        allDay = allDay,
        startTime = time,
        endDate = end.toLocalDate(),
        endTime = end.toLocalTime(),
    )
    return if (allDay) picked.copy(endDate = form.startDate.plusDays(suggestion.days - 1)) else picked
}

/** Second line of a suggestion row, e.g. `Termin · 08:30–09:30`. */
fun describe(suggestion: Suggestion): String {
    val type = suggestion.type.label
    return when {
        suggestion.allDay && suggestion.type == EntryType.APPOINTMENT ->
            if (suggestion.days > 1) "$type · ganztägig, ${suggestion.days} Tage" else "$type · ganztägig"
        suggestion.allDay -> type
        suggestion.type == EntryType.APPOINTMENT ->
            "$type · ${suggestion.startTime.format(CLOCK)}–${suggestion.startTime.plusMinutes(suggestion.length()).format(CLOCK)}"
        else -> "$type · ${suggestion.startTime.format(CLOCK)}"
    }
}
