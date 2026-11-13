package io.github.typhosj.familycalendar

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.time.LocalDate
import java.time.ZoneId

/** The system calendar provider; its sync adapter carries entries to Google. */
class CalendarStore(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun calendars(): List<DeviceCalendar> {
        val columns = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.SYNC_EVENTS,
            Calendars.OWNER_ACCOUNT,
        )
        return resolver.query(Calendars.CONTENT_URI, columns, null, null, null)?.use { row ->
            buildList {
                while (row.moveToNext()) {
                    add(
                        DeviceCalendar(
                            id = row.getLong(0),
                            name = row.getString(1).orEmpty(),
                            accountName = row.getString(2).orEmpty(),
                            accountType = row.getString(3).orEmpty(),
                            accessLevel = row.getInt(4),
                            syncEvents = row.getInt(5) == 1,
                            owner = row.getString(6).orEmpty(),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    /** Occurrences, series expanded, overlapping the local days [from] up to [to], with a day of margin. */
    fun instances(calendarIds: List<Long>, from: LocalDate, to: LocalDate, zone: ZoneId): List<Instance> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            ContentUris.appendId(it, to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }.build()
        val columns = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.CALENDAR_DISPLAY_NAME,
            Instances.CALENDAR_ID,
        )
        val selection = "${Instances.CALENDAR_ID} IN (${calendarIds.joinToString()}) AND ${Instances.VISIBLE} = 1"
        return resolver.query(uri, columns, selection, null, null)?.use { row ->
            buildList {
                while (row.moveToNext()) {
                    add(
                        Instance(
                            eventId = row.getLong(0),
                            title = row.getString(1).orEmpty(),
                            begin = row.getLong(2),
                            end = row.getLong(3),
                            allDay = row.getInt(4) == 1,
                            calendarName = row.getString(5).orEmpty(),
                            calendarId = row.getLong(6),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    /** Calls [onChange] on the main thread whenever calendar data changes; returns the way to stop. */
    fun observe(onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        return { resolver.unregisterContentObserver(observer) }
    }

    /** Event and alert go in one batch, so a failure leaves nothing to clean up; returns the new event ID. */
    fun insert(calendar: DeviceCalendar, entry: Entry): Long {
        val event = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar.id)
            put(Events.TITLE, entry.title)
            put(Events.DTSTART, entry.startMillis)
            entry.endMillis?.let { put(Events.DTEND, it) }
            entry.duration?.let { put(Events.DURATION, it) }
            put(Events.ALL_DAY, if (entry.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, entry.timeZone)
            entry.rrule?.let { put(Events.RRULE, it) }
            put(Events.HAS_ALARM, if (entry.alertMinutes != null) 1 else 0)
        }
        val operations = arrayListOf(ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(event).build())
        entry.alertMinutes?.let { minutes ->
            operations += ContentProviderOperation.newInsert(Reminders.CONTENT_URI)
                .withValueBackReference(Reminders.EVENT_ID, 0)
                .withValue(Reminders.MINUTES, minutes)
                .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
                .build()
        }
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        ContentResolver.requestSync(calendar.account, CalendarContract.AUTHORITY, syncNow())
        return ContentUris.parseId(requireNotNull(results[0].uri))
    }

    /** What editing needs from one event row; null when the row is gone. */
    data class EventRow(val recurring: Boolean, val start: Long, val originalId: Long?)

    fun event(id: Long): EventRow? {
        val columns = arrayOf(Events.RRULE, Events.DTSTART, Events.ORIGINAL_ID)
        return resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), columns, null, null, null)?.use { row ->
            if (!row.moveToFirst()) {
                null
            } else {
                EventRow(
                    recurring = !row.getString(0).isNullOrEmpty(),
                    start = row.getLong(1),
                    originalId = if (row.isNull(2)) null else row.getString(2).toLongOrNull(),
                )
            }
        }
    }

    /** Title and times of a row; an end and a duration exclude each other, so the unused one is cleared. */
    private fun times(entry: Entry) = ContentValues().apply {
        put(Events.TITLE, entry.title)
        put(Events.DTSTART, entry.startMillis)
        if (entry.endMillis != null) put(Events.DTEND, entry.endMillis) else putNull(Events.DTEND)
        if (entry.duration != null) put(Events.DURATION, entry.duration) else putNull(Events.DURATION)
        put(Events.ALL_DAY, if (entry.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, entry.timeZone)
    }

    /** Changes a single entry, or a birthday series as a whole (its RRULE stays). */
    fun update(calendar: DeviceCalendar, eventId: Long, entry: Entry) {
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), times(entry), null, null)
        ContentResolver.requestSync(calendar.account, CalendarContract.AUTHORITY, syncNow())
    }

    /** Changes one occurrence of a series alone: the provider stores it as an exception. */
    fun updateInstance(calendar: DeviceCalendar, eventId: Long, instanceBegin: Long, entry: Entry) {
        val values = times(entry).apply {
            putNull(Events.DURATION)
            put(Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
        }
        resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId), values)
        ContentResolver.requestSync(calendar.account, CalendarContract.AUTHORITY, syncNow())
    }

    /** Deletes a single entry or a whole series; the provider marks it and the sync removes it in Google. */
    fun delete(calendar: DeviceCalendar, eventId: Long) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
        ContentResolver.requestSync(calendar.account, CalendarContract.AUTHORITY, syncNow())
    }

    /** Removes one occurrence of a series: an exception with the status cancelled. */
    fun cancelInstance(calendar: DeviceCalendar, eventId: Long, instanceBegin: Long) {
        val values = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
            put(Events.STATUS, Events.STATUS_CANCELED)
        }
        resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId), values)
        ContentResolver.requestSync(calendar.account, CalendarContract.AUTHORITY, syncNow())
    }

    private val DeviceCalendar.account get() = Account(accountName, accountType)

    private fun syncNow() = Bundle().apply {
        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
    }

    fun masterSyncOn(): Boolean = ContentResolver.getMasterSyncAutomatically()

    /** Switches on the sync of one calendar, as the toggle in the Google Calendar app does. */
    fun enableSync(calendar: DeviceCalendar) {
        val values = ContentValues().apply {
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
        }
        resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar.id), values, null, null)
        ContentResolver.requestSync(calendar.account, CalendarContract.AUTHORITY, syncNow())
    }

    /** Asks every account to sync, so a calendar shared a moment ago can appear. */
    fun refresh() {
        ContentResolver.requestSync(null, CalendarContract.AUTHORITY, syncNow())
    }
}
