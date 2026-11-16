package io.github.typhosj.familycalendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val reminders = 1L
    private val appointments = 2L
    private val typeOf = mapOf(reminders to EntryType.REMINDER, appointments to EntryType.APPOINTMENT)
    private val day = LocalDate.of(2026, 11, 20)
    private val form = Form(
        startDate = day,
        startTime = LocalTime.of(14, 0),
        endDate = day,
        endTime = LocalTime.of(15, 0),
    )

    private fun local(text: String) = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()
    private fun utc(text: String) = LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun timed(title: String, from: String, to: String, calendar: Long = appointments) =
        Instance(0, title, local(from), local(to), false, "", calendar)

    private fun allDay(title: String, from: String, toExclusive: String, calendar: Long = appointments) =
        Instance(0, title, utc(from), utc(toExclusive), true, "", calendar)

    private fun suggestion(
        title: String,
        type: EntryType = EntryType.APPOINTMENT,
        allDay: Boolean = false,
        startTime: LocalTime = LocalTime.of(8, 30),
        minutes: Long = 60,
        days: Long = 0,
        count: Int = 1,
        latest: Long = 0,
    ) = Suggestion(title, type, allDay, startTime, minutes, days, count, latest)

    @Test
    fun `one suggestion per title, ignoring case and whitespace, with the values of the latest start`() {
        val found = suggestions(
            listOf(
                timed("zahnarzt", "2026-03-02T10:00", "2026-03-02T10:30"),
                timed("  Zahnarzt ", "2026-09-01T08:30", "2026-09-01T09:45"),
                timed("ZAHNARZT", "2026-05-01T07:00", "2026-05-01T08:00"),
            ),
            typeOf,
            berlin,
        )
        assertEquals(1, found.size)
        val only = found.single()
        assertEquals("Zahnarzt", only.title)
        assertEquals(EntryType.APPOINTMENT, only.type)
        assertEquals(LocalTime.of(8, 30), only.startTime)
        assertEquals(75L, only.minutes)
        assertEquals(3, only.count)
        assertEquals(local("2026-09-01T08:30"), only.latest)
    }

    @Test
    fun `calendars that are no target and blank titles give no suggestion`() {
        val found = suggestions(
            listOf(
                timed("Privat", "2026-03-02T10:00", "2026-03-02T11:00", calendar = 9),
                timed("   ", "2026-03-02T10:00", "2026-03-02T11:00"),
                timed("", "2026-03-02T10:00", "2026-03-02T11:00"),
            ),
            typeOf,
            berlin,
        )
        assertTrue(found.isEmpty())
        assertTrue(suggestions(emptyList(), typeOf, berlin).isEmpty())
    }

    @Test
    fun `reminder has no length, all-day counts days, a timed length uses the wall clock`() {
        val found = suggestions(
            listOf(
                timed("Müll", "2026-11-01T07:00", "2026-11-01T07:00", calendar = reminders),
                allDay("Urlaub", "2026-08-03", "2026-08-06"),
                // Across the switch to winter time (25.10.2026): 01:30 to 03:30 on the clock.
                timed("Nacht", "2026-10-25T01:30", "2026-10-25T03:30"),
            ),
            typeOf,
            berlin,
        ).associateBy { it.title }
        val reminder = found.getValue("Müll")
        assertEquals(EntryType.REMINDER, reminder.type)
        assertEquals(LocalTime.of(7, 0), reminder.startTime)
        assertEquals(0L, reminder.minutes)
        val holiday = found.getValue("Urlaub")
        assertTrue(holiday.allDay)
        assertEquals(3L, holiday.days)
        assertEquals(0L, holiday.minutes)
        assertEquals(120L, found.getValue("Nacht").minutes)
    }

    @Test
    fun `an end before the start gives zero minutes and an empty all-day entry one day`() {
        val found = suggestions(
            listOf(
                timed("Kaputt", "2026-03-02T10:00", "2026-03-02T09:00"),
                allDay("Leer", "2026-03-02", "2026-03-02"),
            ),
            typeOf,
            berlin,
        ).associateBy { it.title }
        assertEquals(0L, found.getValue("Kaputt").minutes)
        assertEquals(1L, found.getValue("Leer").days)
    }

    @Test
    fun `most frequent first, then the latest start`() {
        val found = suggestions(
            listOf(
                timed("Alt", "2026-01-01T10:00", "2026-01-01T11:00"),
                timed("Neu", "2026-06-01T10:00", "2026-06-01T11:00"),
                timed("Oft", "2026-02-01T10:00", "2026-02-01T11:00"),
                timed("Oft", "2026-02-08T10:00", "2026-02-08T11:00"),
            ),
            typeOf,
            berlin,
        )
        assertEquals(listOf("Oft", "Neu", "Alt"), found.map { it.title })
    }

    @Test
    fun `fewer than two typed characters show nothing`() {
        val all = listOf(suggestion("Zahnarzt"))
        assertTrue(matching("", all).isEmpty())
        assertTrue(matching("z", all).isEmpty())
        assertTrue(matching("   ", all).isEmpty())
        assertTrue(matching(" z ", all).isEmpty())
        assertEquals(listOf("Zahnarzt"), matching("za", all).map { it.title })
    }

    @Test
    fun `typed text matches the start of the title or of a word, never the middle of a word`() {
        val all = listOf(suggestion("Zahnarzt"), suggestion("Kinder-Arzt"), suggestion("Arzttermin"), suggestion("Elternabend Schule"))
        assertEquals(listOf("Kinder-Arzt", "Arzttermin"), matching("arzt", all).map { it.title })
        assertEquals(listOf("Elternabend Schule"), matching("schu", all).map { it.title })
        assertEquals(listOf("Elternabend Schule"), matching("  elternabend   sch ", all).map { it.title })
        assertTrue(matching("xy", all).isEmpty())
    }

    @Test
    fun `at most four matches, in the given order`() {
        val all = (1..6).map { suggestion("Termin $it") }
        assertEquals(listOf("Termin 1", "Termin 2", "Termin 3", "Termin 4"), matching("te", all).map { it.title })
    }

    @Test
    fun `nothing shows once the text equals a title`() {
        val all = listOf(suggestion("Arzt"), suggestion("Arzt Kontrolle"))
        assertTrue(matching("Arzt", all).isEmpty())
        assertTrue(matching(" arzt ", all).isEmpty())
        assertEquals(2, matching("Arz", all).size)
    }

    @Test
    fun `picking a reminder takes text and time, keeps the date, ends an hour later`() {
        val picked = applySuggestion(
            form.copy(type = EntryType.APPOINTMENT, text = "Mü", allDay = true),
            suggestion("Müll", type = EntryType.REMINDER, startTime = LocalTime.of(7, 0), minutes = 0),
        )
        assertEquals(
            form.copy(type = EntryType.REMINDER, text = "Müll", startTime = LocalTime.of(7, 0), endTime = LocalTime.of(8, 0)),
            picked,
        )
    }

    @Test
    fun `picking a timed appointment ends after its length, across midnight`() {
        val picked = applySuggestion(form, suggestion("Nachtdienst", startTime = LocalTime.of(22, 30), minutes = 150))
        assertEquals(EntryType.APPOINTMENT, picked.type)
        assertEquals(day, picked.startDate)
        assertEquals(LocalTime.of(22, 30), picked.startTime)
        assertEquals(day.plusDays(1), picked.endDate)
        assertEquals(LocalTime.of(1, 0), picked.endTime)
        assertEquals(false, picked.allDay)
    }

    @Test
    fun `a zero-length appointment gets an hour`() {
        val picked = applySuggestion(form, suggestion("Anruf", startTime = LocalTime.of(9, 0), minutes = 0))
        assertEquals(LocalTime.of(10, 0), picked.endTime)
        assertEquals(day, picked.endDate)
    }

    @Test
    fun `picking an all-day appointment keeps date and times and counts days inclusive`() {
        val one = applySuggestion(form, suggestion("Ausflug", allDay = true, startTime = LocalTime.MIDNIGHT, minutes = 0, days = 1))
        assertEquals(form.copy(type = EntryType.APPOINTMENT, text = "Ausflug", allDay = true), one)
        val three = applySuggestion(form, suggestion("Urlaub", allDay = true, startTime = LocalTime.MIDNIGHT, minutes = 0, days = 3))
        assertEquals(day, three.startDate)
        assertEquals(day.plusDays(2), three.endDate)
        assertEquals(LocalTime.of(14, 0), three.startTime)
        assertTrue(three.allDay)
    }

    @Test
    fun `an all-day reminder keeps the form's time`() {
        val picked = applySuggestion(
            form,
            suggestion("Feiertag", type = EntryType.REMINDER, allDay = true, startTime = LocalTime.MIDNIGHT, minutes = 0, days = 1),
        )
        assertEquals(form.copy(type = EntryType.REMINDER, text = "Feiertag"), picked)
    }

    @Test
    fun `row texts`() {
        assertEquals("Termin · 08:30–09:30", describe(suggestion("A", startTime = LocalTime.of(8, 30), minutes = 60)))
        assertEquals("Termin · 23:00–01:00", describe(suggestion("A", startTime = LocalTime.of(23, 0), minutes = 120)))
        assertEquals("Termin · 09:00–10:00", describe(suggestion("A", startTime = LocalTime.of(9, 0), minutes = 0)))
        assertEquals("Termin · ganztägig", describe(suggestion("A", allDay = true, days = 1)))
        assertEquals("Termin · ganztägig, 3 Tage", describe(suggestion("A", allDay = true, days = 3)))
        assertEquals("Erinnerung · 07:00", describe(suggestion("A", type = EntryType.REMINDER, startTime = LocalTime.of(7, 0), minutes = 0)))
        assertEquals("Erinnerung", describe(suggestion("A", type = EntryType.REMINDER, allDay = true, days = 1)))
    }
}
