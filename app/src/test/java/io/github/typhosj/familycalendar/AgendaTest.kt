package io.github.typhosj.familycalendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgendaTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = LocalDateTime.of(2026, 11, 11, 10, 0)
    private val today = now.toLocalDate()

    private fun local(text: String) = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()
    private fun utc(date: String) = Instant.parse("${date}T00:00:00Z").toEpochMilli()

    private var nextId = 1L
    private fun timed(start: String, end: String, title: String = "T", calendar: String = "Privat") =
        Instance(nextId++, title, local(start), local(end), false, calendar)

    private fun allDay(start: String, endExclusive: String, title: String = "T", calendar: String = "Termine") =
        Instance(nextId++, title, utc(start), utc(endExclusive), true, calendar)

    private fun item(instance: Instance) = loadPage({ _, _ -> listOf(instance) }, today, 1, now, berlin).items.single()

    @Test
    fun `today is red with its time range and counts as appointment`() {
        val result = item(timed("2026-11-11T12:00", "2026-11-11T13:00"))
        assertEquals("Heute", result.day)
        assertEquals(Tone.NOW, result.tone)
        assertEquals("12:00 - 13:00", result.right)
        assertEquals(EntryType.APPOINTMENT, result.type)
    }

    @Test
    fun `tomorrow and later days`() {
        val tomorrow = item(timed("2026-11-12T12:00", "2026-11-12T13:00"))
        assertEquals("Morgen", tomorrow.day)
        assertEquals(Tone.NEXT, tomorrow.tone)
        val later = item(timed("2026-11-20T12:00", "2026-11-20T13:00"))
        assertEquals("20.11.2026", later.day)
        assertEquals(Tone.PLAIN, later.tone)
    }

    @Test
    fun `reminder shows one time`() {
        val result = item(timed("2026-11-20T08:00", "2026-11-20T08:00", calendar = "Erinnerungen"))
        assertEquals("08:00", result.right)
        assertEquals(EntryType.REMINDER, result.type)
    }

    @Test
    fun `birthday has no right text`() {
        val result = item(allDay("2026-11-20", "2026-11-21", calendar = "Geburtstage"))
        assertEquals("", result.right)
        assertEquals(EntryType.BIRTHDAY, result.type)
    }

    @Test
    fun `all-day stays on its date and shows an inclusive end`() {
        val single = item(allDay("2026-11-14", "2026-11-15"))
        assertEquals("14.11.2026", single.day)
        assertEquals("", single.right)
        assertEquals("bis 16.11.", item(allDay("2026-11-14", "2026-11-17")).right)
    }

    @Test
    fun `timed multi-day shows end date and time`() {
        assertEquals("bis 16.11. 12:00", item(timed("2026-11-14T10:00", "2026-11-16T12:00")).right)
    }

    @Test
    fun `a started multi-day entry is running`() {
        val result = item(allDay("2026-11-09", "2026-11-13"))
        assertEquals("läuft", result.day)
        assertEquals(Tone.NOW, result.tone)
        assertEquals("bis 12.11.", result.right)
    }

    @Test
    fun `an empty title has a placeholder`() {
        assertEquals("(ohne Titel)", item(timed("2026-11-20T12:00", "2026-11-20T13:00", title = "  ")).title)
    }

    private fun load(vararg instances: Instance) =
        loadPage({ _, _ -> instances.toList() }, today, 100, now, berlin).items

    @Test
    fun `ended entries are left out, an end right now counts as ended`() {
        assertEquals(emptyList<AgendaItem>(), load(timed("2026-11-11T09:00", "2026-11-11T10:00")))
        assertEquals(1, load(timed("2026-11-11T09:00", "2026-11-11T10:01")).size)
    }

    @Test
    fun `an all-day entry lasts until local midnight`() {
        assertEquals(1, load(allDay("2026-11-11", "2026-11-12")).size)
        val lateNow = LocalDateTime.of(2026, 11, 11, 23, 59)
        assertEquals(1, loadPage({ _, _ -> listOf(allDay("2026-11-11", "2026-11-12")) }, today, 9, lateNow, berlin).items.size)
        assertEquals(0, load(allDay("2026-11-10", "2026-11-11")).size)
    }

    @Test
    fun `sorted by local start, all-day first on its day`() {
        val result = load(
            timed("2026-11-14T00:30", "2026-11-14T01:00", title = "b"),
            allDay("2026-11-14", "2026-11-15", title = "a"),
            timed("2026-11-13T23:00", "2026-11-13T23:30", title = "c"),
        )
        assertEquals(listOf("c", "a", "b"), result.map { it.title })
    }

    @Test
    fun `stops at the window that reaches the count and says where to go on`() {
        val windows = mutableListOf<Pair<LocalDate, LocalDate>>()
        val page = loadPage(
            { from, to ->
                windows += from to to
                if (from > today) listOf(timed("${from.plusDays(1)}T12:00", "${from.plusDays(1)}T13:00")) else emptyList()
            },
            today,
            1,
            now,
            berlin,
        )
        assertEquals(1, page.items.size)
        assertEquals(2, windows.size)
        assertEquals(today.plusDays(WINDOW_DAYS), windows[1].first)
        assertEquals(today.plusDays(2 * WINDOW_DAYS), page.next)
    }

    @Test
    fun `an entry reaching into the next window is listed once`() {
        val long = timed("2026-12-20T12:00", "2027-02-20T12:00")
        val fetch = { _: LocalDate, _: LocalDate -> listOf(long) }
        val first = loadPage(fetch, today, 1, now, berlin)
        assertEquals(1, first.items.size)
        assertEquals(0, loadPage(fetch, first.next!!, 1, now, berlin).items.size)
    }

    @Test
    fun `a later page leaves out what started before it`() {
        val running = allDay("2026-11-09", "2027-06-01")
        val page = loadPage({ _, _ -> listOf(running) }, today.plusDays(WINDOW_DAYS), 1, now, berlin)
        assertEquals(0, page.items.size)
    }

    @Test
    fun `no entries end at the horizon`() {
        val page = loadPage({ _, _ -> emptyList() }, today, 5, now, berlin)
        assertEquals(0, page.items.size)
        assertNull(page.next)
    }

    @Test
    fun `keys differ between instances of one series`() {
        val first = Instance(7, "s", utc("2026-11-20"), utc("2026-11-21"), true, "Geburtstage")
        val second = first.copy(begin = utc("2027-11-20"), end = utc("2027-11-21"))
        val items = loadPage({ _, _ -> listOf(first, second) }, today, 100, now, berlin).items
        assertEquals(2, items.map { it.key }.toSet().size)
    }
}
