package io.github.typhosj.familycalendar

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

const val MAX_TEXT = 200

/** Entry type and the name of its Google calendar. */
enum class EntryType(val calendarName: String) {
    REMINDER("Erinnerungen"),
    APPOINTMENT("Termine"),
    BIRTHDAY("Geburtstage"),
}

/** What the screen holds; serializable so it survives a rotation. */
data class Form(
    val type: EntryType = EntryType.REMINDER,
    val text: String = "",
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
    val allDay: Boolean = false,
) : Serializable

/** One row for the calendar provider. A series has a duration instead of an end. */
data class Entry(
    val title: String,
    val startMillis: Long,
    val endMillis: Long?,
    val duration: String?,
    val allDay: Boolean,
    val timeZone: String,
    val rrule: String?,
    val alertMinutes: Int?,
)

sealed interface Built {
    data class Ok(val entry: Entry) : Built
    data class Error(val text: String) : Built
}

private fun collapse(text: String): String =
    text.map { if (it.isWhitespace()) ' ' else it }.joinToString("").trim().replace(Regex(" +"), " ")

/** All-day values are UTC midnights in the provider, never shifted through the local zone. */
private fun utc(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Known limit: a time inside the spring-forward gap moves one hour on, as in the adapter. */
private fun local(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
    date.atTime(time).atZone(zone).toInstant().toEpochMilli()

/**
 * Validates the form with the adapter's rules; every rejection is a German sentence for the screen.
 * Known limit: a birthday on 29 February (FREQ=YEARLY) shows only in leap years.
 */
fun buildEntry(form: Form, zone: ZoneId): Built {
    val birthday = form.type == EntryType.BIRTHDAY
    val title = collapse(form.text)
    if (title.isEmpty()) {
        return Built.Error(if (birthday) "Bitte einen Namen eingeben." else "Bitte einen Text eingeben.")
    }
    if (title.length > MAX_TEXT) return Built.Error("Der Text ist länger als $MAX_TEXT Zeichen.")
    if (form.startDate.year !in (if (birthday) 1900 else 2000)..2100) {
        return Built.Error("Bitte ein gültiges Datum eingeben.")
    }
    val entry = Entry(title, 0, null, null, false, zone.id, null, null)
    return when (form.type) {
        EntryType.BIRTHDAY -> Built.Ok(
            entry.copy(
                startMillis = utc(form.startDate),
                duration = "P1D",
                allDay = true,
                timeZone = "UTC",
                rrule = "FREQ=YEARLY",
            ),
        )
        EntryType.REMINDER -> {
            val at = local(form.startDate, form.startTime, zone)
            Built.Ok(entry.copy(startMillis = at, endMillis = at, alertMinutes = 0))
        }
        EntryType.APPOINTMENT -> appointment(form, zone, entry)
    }
}

private fun appointment(form: Form, zone: ZoneId, entry: Entry): Built {
    if (form.endDate.year !in 2000..2100) return Built.Error("Bitte ein gültiges Enddatum eingeben.")
    if (form.allDay) {
        if (form.endDate < form.startDate) return Built.Error("Bitte ein Enddatum ab dem Beginn wählen.")
        return Built.Ok(
            entry.copy(
                startMillis = utc(form.startDate),
                endMillis = utc(form.endDate.plusDays(1)),
                allDay = true,
                timeZone = "UTC",
            ),
        )
    }
    val from = local(form.startDate, form.startTime, zone)
    val to = local(form.endDate, form.endTime, zone)
    if (to <= from) return Built.Error("Bitte ein Ende nach dem Beginn wählen.")
    return Built.Ok(entry.copy(startMillis = from, endMillis = to))
}

/**
 * Keeps the end after the start, with the adapter's rule: an earlier end date takes the start date,
 * a timed end that is not after the start becomes one hour after it.
 */
fun followStart(form: Form): Form {
    val moved = if (form.endDate < form.startDate) form.copy(endDate = form.startDate) else form
    val start = form.startDate.atTime(form.startTime)
    if (form.allDay || moved.endDate.atTime(form.endTime) > start) return moved
    val end = start.plusHours(1)
    return moved.copy(endDate = end.toLocalDate(), endTime = end.toLocalTime())
}

/** Form values on start and after a save: the next full hour, one hour long. */
fun defaults(now: LocalDateTime): Form {
    val start = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
    val end = start.plusHours(1)
    return Form(
        startDate = start.toLocalDate(),
        startTime = start.toLocalTime(),
        endDate = end.toLocalDate(),
        endTime = end.toLocalTime(),
    )
}
