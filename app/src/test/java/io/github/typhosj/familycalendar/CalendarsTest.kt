package io.github.typhosj.familycalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarsTest {
    private fun calendar(
        id: Long,
        name: String,
        accountType: String = "com.google",
        accessLevel: Int = 700,
        syncEvents: Boolean = true,
        owner: String = "owner@example.test",
    ) = DeviceCalendar(id, name, "account", accountType, accessLevel, syncEvents, owner)

    private fun check(
        all: List<DeviceCalendar>,
        savedId: Long? = null,
        type: EntryType = EntryType.APPOINTMENT,
    ) = checkCalendar(type, all, savedId)

    @Test
    fun `a writable synced calendar is fine`() {
        val result = check(listOf(calendar(1, "Termine")))
        assertEquals(CalendarStatus.OK, result.status)
        assertEquals(1L, result.calendar?.id)
    }

    @Test
    fun `contributor access is enough, one level below is read-only`() {
        assertEquals(CalendarStatus.OK, check(listOf(calendar(1, "Termine", accessLevel = 500))).status)
        assertEquals(CalendarStatus.READ_ONLY, check(listOf(calendar(1, "Termine", accessLevel = 499))).status)
    }

    @Test
    fun `no calendar of that name is missing`() {
        assertEquals(CalendarStatus.MISSING, check(emptyList()).status)
        assertEquals(CalendarStatus.MISSING, check(listOf(calendar(1, "Privat"))).status)
    }

    @Test
    fun `a local calendar of that name does not count`() {
        assertEquals(CalendarStatus.MISSING, check(listOf(calendar(1, "Termine", accountType = "LOCAL"))).status)
    }

    @Test
    fun `the contacts birthdays calendar does not count as the family calendar`() {
        val contacts = calendar(1, "Geburtstage", accessLevel = 200, owner = "addressbook#contacts@group.v.calendar.google.com")
        assertEquals(CalendarStatus.MISSING, check(listOf(contacts), type = EntryType.BIRTHDAY).status)
    }

    @Test
    fun `a writable calendar without sync can be switched on`() {
        val result = check(listOf(calendar(1, "Termine", syncEvents = false)))
        assertEquals(CalendarStatus.SYNC_OFF, result.status)
        assertEquals(1L, result.calendar?.id)
    }

    @Test
    fun `missing write access comes before missing sync`() {
        assertEquals(CalendarStatus.READ_ONLY, check(listOf(calendar(1, "Termine", accessLevel = 200, syncEvents = false))).status)
    }

    @Test
    fun `two usable calendars of that name need a pick`() {
        val two = listOf(calendar(1, "Termine"), calendar(2, "Termine"))
        assertEquals(CalendarStatus.AMBIGUOUS, check(two).status)
        assertEquals(CalendarStatus.OK, check(two, savedId = 2).status)
    }

    @Test
    fun `a usable calendar wins over a read-only one of the same name`() {
        val result = check(listOf(calendar(1, "Termine", accessLevel = 200), calendar(2, "Termine")))
        assertEquals(CalendarStatus.OK, result.status)
        assertEquals(2L, result.calendar?.id)
    }

    @Test
    fun `a saved pick under another name is fine, a vanished one falls back to the name`() {
        assertEquals(CalendarStatus.OK, check(listOf(calendar(5, "Privat")), savedId = 5).status)
        assertEquals(CalendarStatus.MISSING, check(listOf(calendar(5, "Privat")), savedId = 9).status)
    }

    @Test
    fun `every problem names the calendar`() {
        val problems = CalendarStatus.entries.filter { it != CalendarStatus.OK }
        for (status in problems) {
            assertTrue(status.name, hint(CalendarCheck(EntryType.REMINDER, status, null)).contains("Erinnerungen"))
        }
    }

    @Test
    fun `only synced Google calendars with write access are targets`() {
        val good = calendar(1, "Termine")
        val contributor = calendar(2, "Termine 2", accessLevel = 500)
        val all = listOf(
            good,
            contributor,
            calendar(3, "Lokal", accountType = "LOCAL"),
            calendar(4, "Lesen", accessLevel = 200),
            calendar(5, "Knapp", accessLevel = 499),
            calendar(6, "Aus", syncEvents = false),
        )
        assertEquals(listOf(good, contributor), writable(all))
    }

    @Test
    fun `the read-only contacts calendar named Geburtstage is never a target`() {
        val contacts = calendar(1, "Geburtstage", accessLevel = 200)
        val family = calendar(2, "Geburtstage")
        assertEquals(family, choose(EntryType.BIRTHDAY, writable(listOf(contacts, family)), null))
    }

    @Test
    fun `finds the calendar of the type by exact name`() {
        val all = listOf(calendar(1, "Termine"), calendar(2, "Erinnerungen"), calendar(3, "Geburtstage"))
        assertEquals(1L, choose(EntryType.APPOINTMENT, all, null)?.id)
        assertEquals(2L, choose(EntryType.REMINDER, all, null)?.id)
        assertEquals(3L, choose(EntryType.BIRTHDAY, all, null)?.id)
    }

    @Test
    fun `no match or an ambiguous name asks the user`() {
        assertNull(choose(EntryType.APPOINTMENT, listOf(calendar(1, "Privat")), null))
        assertNull(choose(EntryType.APPOINTMENT, listOf(calendar(1, "Termine"), calendar(2, "Termine")), null))
        assertNull(choose(EntryType.APPOINTMENT, emptyList(), null))
    }

    @Test
    fun `the saved pick wins over the name`() {
        val all = listOf(calendar(1, "Termine"), calendar(2, "Privat"))
        assertEquals(2L, choose(EntryType.APPOINTMENT, all, 2)?.id)
    }

    @Test
    fun `a saved pick that is gone falls back to the name`() {
        assertEquals(1L, choose(EntryType.APPOINTMENT, listOf(calendar(1, "Termine")), 99)?.id)
    }
}
