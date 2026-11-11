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

class EntryTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 11, 14)
    private val form = Form(
        type = EntryType.REMINDER,
        text = "Test",
        startDate = day,
        startTime = LocalTime.of(9, 0),
        endDate = day,
        endTime = LocalTime.of(10, 0),
    )

    private fun millis(text: String) = Instant.parse(text).toEpochMilli()
    private fun ok(input: Form) = (buildEntry(input, berlin) as Built.Ok).entry
    private fun error(input: Form) = (buildEntry(input, berlin) as Built.Error).text

    @Test
    fun `reminder has zero duration and an alert at the time of the event`() {
        val entry = ok(form)
        assertEquals(millis("2026-11-14T08:00:00Z"), entry.startMillis)
        assertEquals(entry.startMillis, entry.endMillis)
        assertNull(entry.duration)
        assertNull(entry.rrule)
        assertFalse(entry.allDay)
        assertEquals("Europe/Berlin", entry.timeZone)
        assertEquals(0, entry.alertMinutes)
    }

    @Test
    fun `text is collapsed and trimmed, also unicode whitespace`() {
        assertEquals("a b c", ok(form.copy(text = "  a \n\t b  c ")).title)
    }

    @Test
    fun `empty text is rejected with the sentence of the type`() {
        assertEquals("Bitte einen Text eingeben.", error(form.copy(text = " \n ")))
        assertEquals("Bitte einen Namen eingeben.", error(form.copy(type = EntryType.BIRTHDAY, text = "")))
    }

    @Test
    fun `text length boundary`() {
        assertEquals(MAX_TEXT, ok(form.copy(text = "x".repeat(MAX_TEXT))).title.length)
        assertEquals("Der Text ist länger als 200 Zeichen.", error(form.copy(text = "x".repeat(MAX_TEXT + 1))))
    }

    @Test
    fun `reminder and appointment years are 2000 to 2100`() {
        val invalid = "Bitte ein gültiges Datum eingeben."
        assertEquals(invalid, error(form.copy(startDate = LocalDate.of(1999, 12, 31))))
        assertEquals(invalid, error(form.copy(startDate = LocalDate.of(2101, 1, 1))))
        assertTrue(buildEntry(form.copy(startDate = LocalDate.of(2000, 1, 1)), berlin) is Built.Ok)
        assertTrue(buildEntry(form.copy(startDate = LocalDate.of(2100, 12, 31)), berlin) is Built.Ok)
    }

    @Test
    fun `birthday is a yearly all-day series on the exact date`() {
        val entry = ok(form.copy(type = EntryType.BIRTHDAY, startDate = LocalDate.of(1980, 3, 2)))
        assertEquals(millis("1980-03-02T00:00:00Z"), entry.startMillis)
        assertNull(entry.endMillis)
        assertEquals("P1D", entry.duration)
        assertTrue(entry.allDay)
        assertEquals("UTC", entry.timeZone)
        assertEquals("FREQ=YEARLY", entry.rrule)
        assertNull(entry.alertMinutes)
    }

    @Test
    fun `birthday years are 1900 to 2100`() {
        val birthday = form.copy(type = EntryType.BIRTHDAY)
        assertTrue(buildEntry(birthday.copy(startDate = LocalDate.of(1900, 1, 1)), berlin) is Built.Ok)
        assertEquals("Bitte ein gültiges Datum eingeben.", error(birthday.copy(startDate = LocalDate.of(1899, 12, 31))))
    }

    @Test
    fun `timed appointment needs an end after the start`() {
        val appointment = form.copy(type = EntryType.APPOINTMENT)
        val entry = ok(appointment)
        assertEquals(millis("2026-11-14T08:00:00Z"), entry.startMillis)
        assertEquals(millis("2026-11-14T09:00:00Z"), entry.endMillis)
        assertNull(entry.alertMinutes)
        val invalid = "Bitte ein Ende nach dem Beginn wählen."
        assertEquals(invalid, error(appointment.copy(endTime = LocalTime.of(9, 0))))
        assertEquals(invalid, error(appointment.copy(endTime = LocalTime.of(8, 59))))
        assertTrue(buildEntry(appointment.copy(endTime = LocalTime.of(9, 1)), berlin) is Built.Ok)
    }

    @Test
    fun `timed appointment may span days`() {
        val entry = ok(form.copy(type = EntryType.APPOINTMENT, endDate = day.plusDays(2), endTime = LocalTime.of(8, 0)))
        assertEquals(millis("2026-11-16T07:00:00Z"), entry.endMillis)
    }

    @Test
    fun `appointment end year is checked`() {
        assertEquals(
            "Bitte ein gültiges Enddatum eingeben.",
            error(form.copy(type = EntryType.APPOINTMENT, endDate = LocalDate.of(2101, 1, 1))),
        )
    }

    @Test
    fun `all-day end is entered inclusive and stored exclusive`() {
        val allDay = form.copy(type = EntryType.APPOINTMENT, allDay = true)
        val single = ok(allDay)
        assertEquals(millis("2026-11-14T00:00:00Z"), single.startMillis)
        assertEquals(millis("2026-11-15T00:00:00Z"), single.endMillis)
        assertEquals("UTC", single.timeZone)
        assertTrue(single.allDay)
        assertEquals(millis("2026-11-17T00:00:00Z"), ok(allDay.copy(endDate = day.plusDays(2))).endMillis)
    }

    @Test
    fun `all-day ignores the times and rejects an end before the start`() {
        val allDay = form.copy(type = EntryType.APPOINTMENT, allDay = true, endTime = LocalTime.of(1, 0))
        assertTrue(buildEntry(allDay, berlin) is Built.Ok)
        assertEquals("Bitte ein Enddatum ab dem Beginn wählen.", error(allDay.copy(endDate = day.minusDays(1))))
    }

    @Test
    fun `a time in the spring-forward gap moves one hour on`() {
        val gap = form.copy(startDate = LocalDate.of(2026, 3, 29), startTime = LocalTime.of(2, 30))
        assertEquals(millis("2026-03-29T01:30:00Z"), ok(gap).startMillis)
    }

    @Test
    fun `defaults are the next full hour, one hour long`() {
        val result = defaults(LocalDateTime.of(2026, 11, 14, 10, 20))
        assertEquals(day, result.startDate)
        assertEquals(LocalTime.of(11, 0), result.startTime)
        assertEquals(day, result.endDate)
        assertEquals(LocalTime.of(12, 0), result.endTime)
        assertEquals("", result.text)
        assertFalse(result.allDay)
    }

    private val timed = form.copy(type = EntryType.APPOINTMENT, startTime = LocalTime.of(10, 0), endTime = LocalTime.of(11, 0))

    @Test
    fun `an end after the start stays`() {
        assertEquals(timed, followStart(timed))
        val nextDay = timed.copy(endDate = day.plusDays(1), endTime = LocalTime.of(9, 0))
        assertEquals(nextDay, followStart(nextDay))
    }

    @Test
    fun `an earlier end date takes the start date and keeps a later end time`() {
        val result = followStart(timed.copy(endDate = day.minusDays(3), endTime = LocalTime.of(15, 0)))
        assertEquals(day, result.endDate)
        assertEquals(LocalTime.of(15, 0), result.endTime)
    }

    @Test
    fun `an end time not after the start becomes one hour after it`() {
        for (end in listOf(LocalTime.of(10, 0), LocalTime.of(9, 59))) {
            val result = followStart(timed.copy(endTime = end))
            assertEquals(day, result.endDate)
            assertEquals(LocalTime.of(11, 0), result.endTime)
        }
        assertEquals(LocalTime.of(10, 1), followStart(timed.copy(endTime = LocalTime.of(10, 1))).endTime)
    }

    @Test
    fun `date and time move together when both are before the start`() {
        val result = followStart(timed.copy(endDate = day.minusDays(3), endTime = LocalTime.of(9, 0)))
        assertEquals(day, result.endDate)
        assertEquals(LocalTime.of(11, 0), result.endTime)
    }

    @Test
    fun `a late start moves the end over midnight`() {
        val result = followStart(timed.copy(startTime = LocalTime.of(23, 30), endTime = LocalTime.of(23, 0)))
        assertEquals(day.plusDays(1), result.endDate)
        assertEquals(LocalTime.of(0, 30), result.endTime)
    }

    @Test
    fun `all-day moves only the date`() {
        val result = followStart(timed.copy(allDay = true, endDate = day.minusDays(3), endTime = LocalTime.of(9, 0)))
        assertEquals(day, result.endDate)
        assertEquals(LocalTime.of(9, 0), result.endTime)
    }

    @Test
    fun `defaults cross midnight`() {
        val late = defaults(LocalDateTime.of(2026, 11, 14, 22, 30))
        assertEquals(day, late.startDate)
        assertEquals(LocalTime.of(23, 0), late.startTime)
        assertEquals(day.plusDays(1), late.endDate)
        assertEquals(LocalTime.MIDNIGHT, late.endTime)
        val night = defaults(LocalDateTime.of(2026, 11, 14, 23, 10))
        assertEquals(day.plusDays(1), night.startDate)
        assertEquals(LocalTime.MIDNIGHT, night.startTime)
    }

    private val morning = LocalDateTime.of(2026, 11, 14, 10, 20)
    private val suggested = defaults(morning)

    @Test
    fun `an untouched old suggestion moves to the next full hour and keeps the rest`() {
        val typed = suggested.copy(type = EntryType.APPOINTMENT, text = "Zahnarzt", allDay = true)
        val result = refreshSuggestion(typed, suggested, morning.plusHours(2))
        assertEquals(typed.copy(startTime = LocalTime.of(13, 0), endTime = LocalTime.of(14, 0)), result)
    }

    @Test
    fun `an untouched suggestion moves across midnight`() {
        val result = refreshSuggestion(suggested, suggested, LocalDateTime.of(2026, 11, 14, 23, 5))
        assertEquals(day.plusDays(1), result?.startDate)
        assertEquals(LocalTime.MIDNIGHT, result?.startTime)
        assertEquals(LocalTime.of(1, 0), result?.endTime)
    }

    @Test
    fun `nothing changes within the same hour`() {
        assertNull(refreshSuggestion(suggested, suggested, morning.plusMinutes(39)))
    }

    @Test
    fun `a date or time the user chose stays`() {
        val later = morning.plusHours(3)
        assertNull(refreshSuggestion(suggested.copy(startDate = day.plusDays(2)), suggested, later))
        assertNull(refreshSuggestion(suggested.copy(startTime = LocalTime.of(8, 0)), suggested, later))
        assertNull(refreshSuggestion(suggested.copy(endDate = day.plusDays(1)), suggested, later))
        assertNull(refreshSuggestion(suggested.copy(endTime = LocalTime.of(15, 0)), suggested, later))
    }
}
