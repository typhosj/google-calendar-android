package io.github.typhosj.familycalendar

import android.provider.CalendarContract.Events
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowColumnsTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2024, 11, 22)
    private val form = Form(
        type = EntryType.BIRTHDAY,
        text = "Daniel",
        startDate = day,
        startTime = LocalTime.of(9, 0),
        endDate = day,
        endTime = LocalTime.of(10, 0),
    )

    private fun ok(input: Form) = (buildEntry(input, berlin) as Built.Ok).entry

    @Test
    fun `a birthday gets its plain yearly rule, so an imported BYMONTH rule cannot keep the old day`() {
        // Imported series carry FREQ=YEARLY;WKST=MO;INTERVAL=1;BYMONTH=12;BYMONTHDAY=11; with that rule a new
        // DTSTART alone leaves every occurrence on 11 December.
        val columns = rowColumns(ok(form))
        assertEquals("FREQ=YEARLY", columns[Events.RRULE])
        assertEquals(ok(form).startMillis, columns[Events.DTSTART])
        assertEquals("P1D", columns[Events.DURATION])
        assertNull(columns[Events.DTEND])
        assertTrue(columns.containsKey(Events.DTEND))
    }

    @Test
    fun `an entry that is not a series leaves the rule column alone, so a Google series keeps its rule`() {
        val reminder = rowColumns(ok(form.copy(type = EntryType.REMINDER)))
        val appointment = rowColumns(ok(form.copy(type = EntryType.APPOINTMENT)))
        assertFalse(reminder.containsKey(Events.RRULE))
        assertFalse(appointment.containsKey(Events.RRULE))
    }

    @Test
    fun `an empty rule is not written, so it cannot clear a series`() {
        val entry = ok(form).copy(rrule = "")
        assertFalse(rowColumns(entry).containsKey(Events.RRULE))
    }

    @Test
    fun `a timed entry clears the duration and keeps its end`() {
        val columns = rowColumns(ok(form.copy(type = EntryType.REMINDER)))
        assertNull(columns[Events.DURATION])
        assertTrue(columns.containsKey(Events.DURATION))
        assertEquals(columns[Events.DTSTART], columns[Events.DTEND])
        assertEquals(0, columns[Events.ALL_DAY])
    }
}
