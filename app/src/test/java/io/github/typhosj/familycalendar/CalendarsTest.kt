package io.github.typhosj.familycalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarsTest {
    private fun calendar(
        id: Long,
        name: String,
        accountType: String = "com.google",
        accessLevel: Int = 700,
        syncEvents: Boolean = true,
    ) = DeviceCalendar(id, name, "account", accountType, accessLevel, syncEvents)

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
