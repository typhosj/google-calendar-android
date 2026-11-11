# Familienkalender

One-screen Android app that captures reminders, appointments and birthdays into Google calendars
through the system calendar provider. Companion of `ioBroker.google-calendar`; it talks to the
phone's calendar only, never to ioBroker.

## Setup

1. Share the calendars `Erinnerungen`, `Termine` and `Geburtstage` with your Google account with
   the right to make changes, and switch on their sync in the Google Calendar app.
2. Set the default notification of `Erinnerungen` to "at time of event".
3. Install the APK from the GitHub releases and allow calendar access.

The app finds the calendars by name. If a name is missing or ambiguous, pick the calendar once on
the screen; the choice is kept per entry type.

Editing and deleting stay with the Google Calendar app.

## Build

Needs JDK 17 or newer and the Android SDK (platform 36).

    ./gradlew testDebugUnitTest lintDebug assembleDebug

For a release build, put `keystore.properties` next to `settings.gradle.kts` with `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`. The file and the key never go into the repository.

## License

MIT
