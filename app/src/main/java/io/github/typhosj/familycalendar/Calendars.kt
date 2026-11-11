package io.github.typhosj.familycalendar

private const val GOOGLE_ACCOUNT = "com.google"

/** CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR. */
private const val ACCESS_CONTRIBUTOR = 500

/** Google's birthdays-from-contacts calendar is owned by an address of this form. */
private const val CONTACTS_OWNER = "#contacts@"

data class DeviceCalendar(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val accessLevel: Int,
    val syncEvents: Boolean,
    val owner: String = "",
)

/** Calendars an entry can reach Google through. */
fun writable(all: List<DeviceCalendar>): List<DeviceCalendar> =
    all.filter { it.accountType == GOOGLE_ACCOUNT && it.accessLevel >= ACCESS_CONTRIBUTOR && it.syncEvents }

/** The user's saved pick, else the one calendar with the type's name, else null: the user picks. */
fun choose(type: EntryType, candidates: List<DeviceCalendar>, savedId: Long?): DeviceCalendar? =
    candidates.find { it.id == savedId } ?: candidates.filter { it.name == type.calendarName }.singleOrNull()

enum class CalendarStatus { OK, MISSING, READ_ONLY, SYNC_OFF, AMBIGUOUS }

/** Whether entries of a type can reach Google, and the calendar a fix applies to. */
data class CalendarCheck(val type: EntryType, val status: CalendarStatus, val calendar: DeviceCalendar?)

/**
 * Finds out what, if anything, keeps entries of [type] from reaching their Google calendar.
 * Known limit: sync switched off for a whole account goes unnoticed; Android reports it as off to
 * this app even while the account syncs, so asking would warn everyone falsely.
 */
fun checkCalendar(type: EntryType, all: List<DeviceCalendar>, savedId: Long?): CalendarCheck {
    val usable = writable(all)
    val chosen = choose(type, usable, savedId)
    if (chosen != null) return CalendarCheck(type, CalendarStatus.OK, chosen)
    if (usable.count { it.name == type.calendarName } > 1) return CalendarCheck(type, CalendarStatus.AMBIGUOUS, null)
    val named = all.filter {
        it.name == type.calendarName && it.accountType == GOOGLE_ACCOUNT && !it.owner.contains(CONTACTS_OWNER)
    }
    if (named.isEmpty()) return CalendarCheck(type, CalendarStatus.MISSING, null)
    val unsynced = named.find { it.accessLevel >= ACCESS_CONTRIBUTOR }
    return if (unsynced != null) {
        CalendarCheck(type, CalendarStatus.SYNC_OFF, unsynced)
    } else {
        CalendarCheck(type, CalendarStatus.READ_ONLY, named.first())
    }
}

/** What the user reads about a problem, in German. */
fun hint(check: CalendarCheck): String {
    val name = check.type.calendarName
    return when (check.status) {
        CalendarStatus.OK -> ""
        CalendarStatus.MISSING ->
            "Der Kalender „$name“ fehlt auf diesem Handy. Bitte den Besitzer, ihn mit deinem Google-Konto zu " +
                "teilen (Recht: „Änderungen an Terminen vornehmen“), und nimm die Einladung an."
        CalendarStatus.READ_ONLY ->
            "Du darfst den Kalender „$name“ nur lesen. Bitte den Besitzer um das Recht " +
                "„Änderungen an Terminen vornehmen“."
        CalendarStatus.SYNC_OFF -> "Der Kalender „$name“ wird auf diesem Handy nicht synchronisiert."
        CalendarStatus.AMBIGUOUS ->
            "Es gibt mehrere Kalender mit dem Namen „$name“. Bitte wähle unten beim passenden Typ einen aus."
    }
}
