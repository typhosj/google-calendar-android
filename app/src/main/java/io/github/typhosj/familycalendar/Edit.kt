package io.github.typhosj.familycalendar

import java.io.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The entry the form edits; serializable so edit mode survives a rotation. */
data class Editing(
    val eventId: Long,
    val calendarId: Long,
    val type: EntryType,
    /** The event row carries an RRULE: a birthday series, or a series created in Google. */
    val recurring: Boolean,
    /** Instances.BEGIN of the tapped occurrence, the ORIGINAL_INSTANCE_TIME of an exception. */
    val instanceBegin: Long,
    val allDay: Boolean,
    /** The row is a changed occurrence of a Google series (it has an ORIGINAL_ID); it is cancelled, never deleted. */
    val exception: Boolean = false,
) : Serializable

/** The type a list row can be edited as, or null when its calendar is not the family calendar of its type. */
fun editableType(instance: Instance, targets: Map<EntryType, DeviceCalendar?>): EntryType? {
    val type = EntryType.entries.find { it.calendarName == instance.calendarName } ?: return null
    return type.takeIf { targets[type]?.id == instance.calendarId }
}

/** What editing needs from one event row. */
data class EventRow(val recurring: Boolean, val start: Long, val originalId: Long?, val allDay: Boolean)

/**
 * The event a tap edits: the tapped row itself, or for a birthday its series master, so the series
 * changes as a whole. [master] is the row named by [row]'s ORIGINAL_ID, looked up only for a changed
 * birthday occurrence. Null when nothing can be edited: the master is gone, or it is not all-day
 * (its date would depend on the time zone).
 */
fun editingFor(instance: Instance, type: EntryType, row: EventRow, master: EventRow?): Editing? {
    if (type != EntryType.BIRTHDAY) {
        return Editing(
            instance.eventId,
            instance.calendarId,
            type,
            row.recurring,
            instance.begin,
            instance.allDay,
            exception = row.originalId != null,
        )
    }
    val series = (if (row.originalId != null) master else row) ?: return null
    if (!series.allDay) return null
    return Editing(row.originalId ?: instance.eventId, instance.calendarId, type, series.recurring, instance.begin, instance.allDay)
}

private fun at(millis: Long, zone: ZoneId): LocalDateTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

/**
 * Form values for a list row, with the adapter's rules: an all-day end is shown inclusive, a birthday
 * shows the start of its series (the real birth date), a reminder's hidden end is one hour after its
 * start. Times an entry does not have come from the usual suggestion. [masterStart] is read as an
 * all-day start in UTC; only all-day masters get here, [editingFor] turns a timed one away.
 */
fun formFor(instance: Instance, type: EntryType, masterStart: Long?, now: LocalDateTime, zone: ZoneId): Form {
    val suggested = defaults(now)
    val base = suggested.copy(type = type, text = instance.title)
    if (type == EntryType.BIRTHDAY) {
        val day = masterStart?.let { at(it, ZoneOffset.UTC).toLocalDate() }
            ?: at(instance.begin, if (instance.allDay) ZoneOffset.UTC else zone).toLocalDate()
        return base.copy(startDate = day, endDate = day, allDay = false)
    }
    if (instance.allDay) {
        val start = at(instance.begin, ZoneOffset.UTC).toLocalDate()
        val last = at(instance.end, ZoneOffset.UTC).toLocalDate().minusDays(1)
        return base.copy(
            startDate = start,
            endDate = if (last < start) start else last,
            allDay = type == EntryType.APPOINTMENT,
        )
    }
    val start = at(instance.begin, zone)
    val end = at(instance.end, zone).let { if (it > start) it else start.plusHours(1) }
    return base.copy(
        startDate = start.toLocalDate(),
        startTime = start.toLocalTime(),
        endDate = end.toLocalDate(),
        endTime = end.toLocalTime(),
        allDay = false,
    )
}

enum class SavePlan { UPDATE_ROW, UPDATE_INSTANCE, MOVE_FROM_ROW, MOVE_FROM_INSTANCE }

/** A birthday changes as a series; one occurrence of another series changes alone, as an exception. */
private fun Editing.alone(): Boolean = recurring && type != EntryType.BIRTHDAY

fun planSave(editing: Editing, newType: EntryType): SavePlan = when {
    newType == editing.type && editing.alone() -> SavePlan.UPDATE_INSTANCE
    newType == editing.type -> SavePlan.UPDATE_ROW
    editing.alone() -> SavePlan.MOVE_FROM_INSTANCE
    else -> SavePlan.MOVE_FROM_ROW
}

enum class DeletePlan { DELETE_ROW, CANCEL_INSTANCE, CANCEL_ROW }

/**
 * Deleting the row of a changed occurrence would only drop the change, and Google brings the original
 * occurrence back (seen on the device); such a row gets the status cancelled instead.
 */
fun planDelete(editing: Editing): DeletePlan = when {
    editing.exception && editing.type != EntryType.BIRTHDAY -> DeletePlan.CANCEL_ROW
    editing.alone() -> DeletePlan.CANCEL_INSTANCE
    else -> DeletePlan.DELETE_ROW
}

enum class DeleteAction { ASK, DELETE }

/** The first press turns the button into "Wirklich löschen?"; any change of the form turns it back. */
fun onDeletePress(confirming: Boolean): DeleteAction = if (confirming) DeleteAction.DELETE else DeleteAction.ASK

/** What the form edits after a type change whose delete of the old entry failed: the new entry. */
fun afterMove(newId: Long, calendar: DeviceCalendar, type: EntryType, startMillis: Long, allDay: Boolean): Editing =
    Editing(newId, calendar.id, type, type == EntryType.BIRTHDAY, startMillis, allDay)
