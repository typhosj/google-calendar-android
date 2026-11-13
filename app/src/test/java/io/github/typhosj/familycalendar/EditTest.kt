package io.github.typhosj.familycalendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = LocalDateTime.of(2026, 11, 13, 14, 20)

    private fun local(text: String) = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()
    private fun utc(date: String) = Instant.parse("${date}T00:00:00Z").toEpochMilli()

    private fun calendar(id: Long, name: String) = DeviceCalendar(id, name, "a@example.test", "com.google", 700, true)
    private val termine = calendar(1, "Termine")
    private val erinnerungen = calendar(2, "Erinnerungen")
    private val geburtstage = calendar(3, "Geburtstage")
    private val targets = mapOf(
        EntryType.APPOINTMENT to termine,
        EntryType.REMINDER to erinnerungen,
        EntryType.BIRTHDAY to geburtstage,
    )

    private fun timed(start: String, end: String, calendarName: String = "Termine", calendarId: Long = 1) =
        Instance(10, "Zahnarzt", local(start), local(end), false, calendarName, calendarId)

    private fun allDay(start: String, endExclusive: String, calendarName: String = "Termine", calendarId: Long = 1) =
        Instance(11, "Urlaub", utc(start), utc(endExclusive), true, calendarName, calendarId)

    private fun editing(type: EntryType, recurring: Boolean) = Editing(10, 1, type, recurring, local("2026-11-19T18:00"), false)

    @Test
    fun `a row of a family calendar is editable as its type`() {
        assertEquals(EntryType.APPOINTMENT, editableType(timed("2026-11-19T18:00", "2026-11-19T19:00"), targets))
        assertEquals(EntryType.REMINDER, editableType(timed("2026-11-19T18:00", "2026-11-19T18:00", "Erinnerungen", 2), targets))
        assertEquals(EntryType.BIRTHDAY, editableType(allDay("2026-11-15", "2026-11-16", "Geburtstage", 3), targets))
    }

    @Test
    fun `a personal calendar, a same-named other calendar and an unresolved type are not editable`() {
        assertNull(editableType(timed("2026-11-19T18:00", "2026-11-19T19:00", "Privat", 9), targets))
        assertNull(editableType(timed("2026-11-19T18:00", "2026-11-19T19:00", "Termine", 9), targets))
        assertNull(editableType(timed("2026-11-19T18:00", "2026-11-19T19:00"), targets + (EntryType.APPOINTMENT to null)))
        assertNull(editableType(timed("2026-11-19T18:00", "2026-11-19T19:00"), emptyMap()))
    }

    @Test
    fun `a timed appointment fills start and end, also across days`() {
        val form = formFor(timed("2026-11-19T18:00", "2026-11-21T12:00"), EntryType.APPOINTMENT, null, now, berlin)
        assertEquals(EntryType.APPOINTMENT, form.type)
        assertEquals("Zahnarzt", form.text)
        assertEquals(LocalDate.of(2026, 11, 19), form.startDate)
        assertEquals(LocalTime.of(18, 0), form.startTime)
        assertEquals(LocalDate.of(2026, 11, 21), form.endDate)
        assertEquals(LocalTime.of(12, 0), form.endTime)
        assertFalse(form.allDay)
    }

    @Test
    fun `a reminder gets an end one hour later, so switching it to an appointment is valid`() {
        val form = formFor(timed("2026-12-31T23:30", "2026-12-31T23:30", "Erinnerungen", 2), EntryType.REMINDER, null, now, berlin)
        assertEquals(LocalTime.of(23, 30), form.startTime)
        assertEquals(LocalDate.of(2027, 1, 1), form.endDate)
        assertEquals(LocalTime.of(0, 30), form.endTime)
        assertTrue(buildEntry(form.copy(type = EntryType.APPOINTMENT), berlin) is Built.Ok)
    }

    @Test
    fun `a timed row whose end is before its start gets an end one hour after the start`() {
        val form = formFor(timed("2026-11-19T18:00", "2026-11-19T17:00"), EntryType.APPOINTMENT, null, now, berlin)
        assertEquals(LocalDate.of(2026, 11, 19), form.endDate)
        assertEquals(LocalTime.of(19, 0), form.endTime)
    }

    @Test
    fun `an all-day appointment shows its last day inclusive and suggests times`() {
        val one = formFor(allDay("2026-11-14", "2026-11-15"), EntryType.APPOINTMENT, null, now, berlin)
        assertTrue(one.allDay)
        assertEquals(LocalDate.of(2026, 11, 14), one.startDate)
        assertEquals(LocalDate.of(2026, 11, 14), one.endDate)
        assertEquals(LocalTime.of(15, 0), one.startTime)
        assertEquals(LocalTime.of(16, 0), one.endTime)
        assertEquals(LocalDate.of(2026, 11, 16), formFor(allDay("2026-11-14", "2026-11-17"), EntryType.APPOINTMENT, null, now, berlin).endDate)
        assertEquals(LocalDate.of(2026, 12, 31), formFor(allDay("2026-12-31", "2027-01-01"), EntryType.APPOINTMENT, null, now, berlin).endDate)
        assertEquals(LocalDate.of(2026, 11, 14), formFor(allDay("2026-11-14", "2026-11-14"), EntryType.APPOINTMENT, null, now, berlin).endDate)
    }

    @Test
    fun `an all-day reminder opens with its time visible`() {
        val form = formFor(allDay("2026-11-14", "2026-11-15", "Erinnerungen", 2), EntryType.REMINDER, null, now, berlin)
        assertFalse(form.allDay)
        assertEquals(LocalTime.of(15, 0), form.startTime)
    }

    @Test
    fun `a birthday shows the date of its series start, else its own day`() {
        val instance = allDay("2026-11-15", "2026-11-16", "Geburtstage", 3)
        val form = formFor(instance, EntryType.BIRTHDAY, utc("1950-11-15"), now, berlin)
        assertEquals(LocalDate.of(1950, 11, 15), form.startDate)
        assertEquals(LocalDate.of(1950, 11, 15), form.endDate)
        assertFalse(form.allDay)
        assertEquals(LocalDate.of(2026, 11, 15), formFor(instance, EntryType.BIRTHDAY, null, now, berlin).startDate)
        val timedBirthday = timed("2026-11-15T09:00", "2026-11-15T10:00", "Geburtstage", 3)
        assertEquals(LocalDate.of(1950, 11, 15), formFor(timedBirthday, EntryType.BIRTHDAY, utc("1950-11-15"), now, berlin).startDate)
    }

    @Test
    fun `save changes a row, one instance of a series, or moves the entry on a type change`() {
        assertEquals(SavePlan.UPDATE_ROW, planSave(editing(EntryType.APPOINTMENT, false), EntryType.APPOINTMENT))
        assertEquals(SavePlan.UPDATE_ROW, planSave(editing(EntryType.BIRTHDAY, true), EntryType.BIRTHDAY))
        assertEquals(SavePlan.UPDATE_INSTANCE, planSave(editing(EntryType.APPOINTMENT, true), EntryType.APPOINTMENT))
        assertEquals(SavePlan.UPDATE_INSTANCE, planSave(editing(EntryType.REMINDER, true), EntryType.REMINDER))
        assertEquals(SavePlan.MOVE_FROM_ROW, planSave(editing(EntryType.REMINDER, false), EntryType.APPOINTMENT))
        assertEquals(SavePlan.MOVE_FROM_ROW, planSave(editing(EntryType.BIRTHDAY, true), EntryType.APPOINTMENT))
        assertEquals(SavePlan.MOVE_FROM_INSTANCE, planSave(editing(EntryType.APPOINTMENT, true), EntryType.REMINDER))
        assertEquals(SavePlan.UPDATE_ROW, planSave(editing(EntryType.BIRTHDAY, false), EntryType.BIRTHDAY))
        assertEquals(SavePlan.MOVE_FROM_INSTANCE, planSave(editing(EntryType.REMINDER, true), EntryType.APPOINTMENT))
        assertEquals(SavePlan.MOVE_FROM_ROW, planSave(editing(EntryType.BIRTHDAY, true), EntryType.REMINDER))
    }

    @Test
    fun `delete removes a row or a birthday series, and cancels one instance of another series`() {
        assertEquals(DeletePlan.DELETE_ROW, planDelete(editing(EntryType.APPOINTMENT, false)))
        assertEquals(DeletePlan.DELETE_ROW, planDelete(editing(EntryType.BIRTHDAY, true)))
        assertEquals(DeletePlan.DELETE_ROW, planDelete(editing(EntryType.BIRTHDAY, false)))
        assertEquals(DeletePlan.CANCEL_INSTANCE, planDelete(editing(EntryType.APPOINTMENT, true)))
        assertEquals(DeletePlan.CANCEL_INSTANCE, planDelete(editing(EntryType.REMINDER, true)))
    }

    private val birthday = allDay("2026-11-15", "2026-11-16", "Geburtstage", 3)

    private fun row(recurring: Boolean = false, originalId: Long? = null, allDay: Boolean = true) =
        EventRow(recurring, utc("1950-11-15"), originalId, allDay)

    @Test
    fun `a plain row is edited as itself`() {
        val instance = timed("2026-11-19T18:00", "2026-11-19T19:00")
        assertEquals(
            Editing(10, 1, EntryType.APPOINTMENT, false, instance.begin, false),
            editingFor(instance, EntryType.APPOINTMENT, row(allDay = false), null),
        )
    }

    @Test
    fun `a changed occurrence of a Google series is edited as its own row, not as the series`() {
        val instance = timed("2026-11-19T18:00", "2026-11-19T19:00")
        assertEquals(
            Editing(10, 1, EntryType.APPOINTMENT, false, instance.begin, false),
            editingFor(instance, EntryType.APPOINTMENT, row(originalId = 77, allDay = false), null),
        )
    }

    @Test
    fun `a birthday master is edited as the series`() {
        assertEquals(
            Editing(11, 3, EntryType.BIRTHDAY, true, birthday.begin, true),
            editingFor(birthday, EntryType.BIRTHDAY, row(recurring = true), null),
        )
    }

    @Test
    fun `a changed birthday occurrence is edited through its master`() {
        assertEquals(
            Editing(77, 3, EntryType.BIRTHDAY, true, birthday.begin, true),
            editingFor(birthday, EntryType.BIRTHDAY, row(originalId = 77), row(recurring = true)),
        )
    }

    @Test
    fun `a birthday without its master or with a timed master opens nothing`() {
        assertNull(editingFor(birthday, EntryType.BIRTHDAY, row(originalId = 77), null))
        assertNull(editingFor(birthday, EntryType.BIRTHDAY, row(originalId = 77), row(recurring = true, allDay = false)))
        assertNull(editingFor(birthday, EntryType.BIRTHDAY, row(recurring = true, allDay = false), null))
    }

    @Test
    fun `the first delete press asks, the second deletes`() {
        assertEquals(DeleteAction.ASK, onDeletePress(confirming = false))
        assertEquals(DeleteAction.DELETE, onDeletePress(confirming = true))
    }

    @Test
    fun `after a move with a failed delete the form edits the new entry`() {
        val moved = afterMove(42, erinnerungen, EntryType.REMINDER, local("2026-11-19T18:00"), false)
        assertEquals(Editing(42, 2, EntryType.REMINDER, false, local("2026-11-19T18:00"), false), moved)
        assertTrue(afterMove(43, geburtstage, EntryType.BIRTHDAY, utc("1950-11-15"), true).recurring)
    }
}
