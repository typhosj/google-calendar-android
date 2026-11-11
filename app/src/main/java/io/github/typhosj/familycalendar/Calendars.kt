package io.github.typhosj.familycalendar

private const val GOOGLE_ACCOUNT = "com.google"

/** CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR. */
private const val ACCESS_CONTRIBUTOR = 500

data class DeviceCalendar(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val accessLevel: Int,
    val syncEvents: Boolean,
)

/** Calendars an entry can reach Google through. */
fun writable(all: List<DeviceCalendar>): List<DeviceCalendar> =
    all.filter { it.accountType == GOOGLE_ACCOUNT && it.accessLevel >= ACCESS_CONTRIBUTOR && it.syncEvents }

/** The user's saved pick, else the one calendar with the type's name, else null: the user picks. */
fun choose(type: EntryType, candidates: List<DeviceCalendar>, savedId: Long?): DeviceCalendar? =
    candidates.find { it.id == savedId } ?: candidates.filter { it.name == type.calendarName }.singleOrNull()
