package io.github.typhosj.familycalendar

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GOOGLE_CALENDAR = "com.google.android.calendar"
private val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
private val DATE_TEXT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val TIME_TEXT = DateTimeFormatter.ofPattern("HH:mm")

/** Entries shown at first, and how many more each time the end of the list is reached. */
private const val AGENDA_PAGE = 5

/** A sync changes many rows in a burst; the list reloads once it has been quiet this long. */
private const val RELOAD_DELAY_MS = 300L

private val EntryType.label: String
    get() = when (this) {
        EntryType.REMINDER -> "Erinnerung"
        EntryType.APPOINTMENT -> "Termin"
        EntryType.BIRTHDAY -> "Geburtstag"
    }

private val EntryType.icon: Int
    get() = when (this) {
        EntryType.REMINDER -> R.drawable.ic_reminder
        EntryType.APPOINTMENT -> R.drawable.ic_appointment
        EntryType.BIRTHDAY -> R.drawable.ic_birthday
    }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

private fun granted(context: Context) =
    PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun pickDate(context: Context, value: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, year, month, day -> onPick(LocalDate.of(year, month + 1, day)) },
        value.year,
        value.monthValue - 1,
        value.dayOfMonth,
    ).show()
}

private fun pickTime(context: Context, value: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, hour, minute -> onPick(LocalTime.of(hour, minute)) }, value.hour, value.minute, true).show()
}

@Composable
private fun Screen() {
    val context = LocalContext.current
    val store = remember { CalendarStore(context) }
    val prefs = remember { context.getSharedPreferences("targets", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var form by rememberSaveable { mutableStateOf(defaults(LocalDateTime.now())) }
    /** The form as last suggested, to tell an untouched date or time from one the user chose. */
    var suggestion by rememberSaveable { mutableStateOf(form) }
    var message by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    /** The entry the form edits; null for a new entry. */
    var editing by rememberSaveable { mutableStateOf<Editing?>(null) }
    /** "Löschen" was pressed once; any change of the form turns it back. */
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(granted(context)) }
    var allCalendars by remember { mutableStateOf(emptyList<DeviceCalendar>()) }
    val calendars = remember(allCalendars) { writable(allCalendars) }
    fun savedId(type: EntryType) = prefs.getLong(type.name, -1).takeIf { it >= 0 }
    var target by remember(form.type, calendars) { mutableStateOf(choose(form.type, calendars, savedId(form.type))) }
    var problems by remember { mutableStateOf(emptyList<CalendarCheck>()) }
    var masterSync by remember { mutableStateOf(true) }
    var picks by remember { mutableIntStateOf(0) }
    val targets = EntryType.entries.associateWith { choose(it, calendars, savedId(it)) }
    val zone = ZoneId.systemDefault()

    fun change(next: Form) {
        form = next
        confirmDelete = false
    }

    val updater = remember { Updater(context) }
    var newVersion by rememberSaveable { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }

    var agenda by remember { mutableStateOf(emptyList<AgendaItem>()) }
    var nextDay by remember { mutableStateOf<LocalDate?>(null) }
    var shown by remember { mutableIntStateOf(AGENDA_PAGE) }
    var loaded by remember { mutableStateOf(false) }
    var changes by remember { mutableIntStateOf(0) }
    var reloads by remember { mutableIntStateOf(0) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = granted(context)
    }
    LaunchedEffect(Unit) { if (!hasPermission) request.launch(PERMISSIONS) }
    LaunchedEffect(Unit) { newVersion = withContext(Dispatchers.IO) { updater.available() } }
    LaunchedEffect(hasPermission, changes, picks) {
        if (!hasPermission) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val all = store.calendars()
            val checks = EntryType.entries.map { checkCalendar(it, all, savedId(it)) }
            Triple(all, checks.filter { it.status != CalendarStatus.OK }, store.masterSyncOn())
        }.let { (all, found, master) ->
            allCalendars = all
            problems = found
            masterSync = master
        }
    }

    // Own saves, syncs and other apps all change the provider; a resume catches a new day.
    DisposableEffect(hasPermission) {
        val stop = if (hasPermission) store.observe { changes++ } else null
        onDispose { stop?.invoke() }
    }
    LifecycleResumeEffect(Unit) {
        hasPermission = granted(context)
        changes++
        if (editing == null) {
            refreshSuggestion(form, suggestion, LocalDateTime.now())?.let {
                form = it
                suggestion = it
            }
        }
        onPauseOrDispose { }
    }

    fun fetch(from: LocalDate, to: LocalDate): List<Instance> =
        store.instances(calendars.map { it.id }, from, to, ZoneId.systemDefault())

    LaunchedEffect(calendars, changes) {
        if (calendars.isEmpty()) return@LaunchedEffect
        delay(RELOAD_DELAY_MS)
        val page = withContext(Dispatchers.IO) {
            loadPage(::fetch, LocalDate.now(), shown, LocalDateTime.now(), ZoneId.systemDefault())
        }
        agenda = page.items
        nextDay = page.next
        loaded = true
        reloads++
    }

    val visible = agenda.take(shown)
    val hasMore = shown < agenda.size || nextDay != null
    LaunchedEffect(listState, visible.size, hasMore, reloads) {
        if (!loaded || !hasMore) return@LaunchedEffect
        // The form is item 0; comparing the total skips a layout from before the list grew.
        val total = visible.size + 1
        snapshotFlow {
            listState.layoutInfo.run { totalItemsCount == total && visibleItemsInfo.lastOrNull()?.index == total - 1 }
        }.first { it }
        val from = nextDay
        if (shown + AGENDA_PAGE > agenda.size && from != null) {
            val page = withContext(Dispatchers.IO) {
                loadPage(::fetch, from, AGENDA_PAGE, LocalDateTime.now(), ZoneId.systemDefault())
            }
            agenda = (agenda + page.items).distinctBy { it.key }
            nextDay = page.next
        }
        shown += AGENDA_PAGE
    }

    fun startNew(text: String) {
        editing = null
        confirmDelete = false
        form = defaults(LocalDateTime.now()).copy(type = form.type)
        suggestion = form
        message = text
    }

    fun openEntry(item: AgendaItem) {
        val instance = item.instance
        if (saving) return
        val type = editableType(instance, targets) ?: return
        scope.launch {
            // A changed occurrence of a series is its own row; a birthday still changes as the series.
            val looked = withContext(Dispatchers.IO) {
                runCatching {
                    val found = store.event(instance.eventId)
                    val master = found?.originalId?.takeIf { type == EntryType.BIRTHDAY }?.let { store.event(it) }
                    found to master
                }
            }
            looked.exceptionOrNull()?.let {
                Log.w("Familienkalender", "open failed: ${it.javaClass.simpleName}")
                message = "Das hat nicht geklappt. Bitte später noch einmal versuchen."
                return@launch
            }
            val (row, master) = looked.getOrThrow()
            val next = row?.let { editingFor(instance, type, it, master) }
            if (row == null || next == null) {
                message = "Diesen Eintrag gibt es nicht mehr."
                return@launch
            }
            form = formFor(instance, type, if (type == EntryType.BIRTHDAY) (master ?: row).start else null, LocalDateTime.now(), zone)
            editing = next
            confirmDelete = false
            message = ""
            listState.animateScrollToItem(0)
        }
    }

    fun save() {
        val entry = when (val built = buildEntry(form, zone)) {
            is Built.Error -> {
                message = built.text
                return
            }
            is Built.Ok -> built.entry
        }
        val calendar = target
        if (calendar == null) {
            message = "Bitte einen Kalender wählen."
            return
        }
        val current = editing
        val old = current?.let { e -> calendars.find { it.id == e.calendarId } }
        if (current != null && old == null) {
            message = "Diesen Eintrag gibt es nicht mehr."
            return
        }
        val type = form.type
        saving = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (current == null || old == null) {
                        store.insert(calendar, entry)
                        null
                    } else {
                        when (planSave(current, type)) {
                            SavePlan.UPDATE_ROW -> store.update(old, current.eventId, entry).let { null }
                            SavePlan.UPDATE_INSTANCE -> store.updateInstance(old, current.eventId, current.instanceBegin, entry).let { null }
                            SavePlan.MOVE_FROM_ROW, SavePlan.MOVE_FROM_INSTANCE -> {
                                // Known limit: a type change can leave a duplicate when the app is killed
                                // between insert and delete; the user deletes it from the list.
                                val newId = store.insert(calendar, entry)
                                val removed = runCatching {
                                    if (planDelete(current) == DeletePlan.CANCEL_INSTANCE) {
                                        store.cancelInstance(old, current.eventId, current.instanceBegin)
                                    } else {
                                        store.delete(old, current.eventId)
                                    }
                                }
                                if (removed.isFailure) {
                                    Log.w("Familienkalender", "delete after move failed: ${removed.exceptionOrNull()?.javaClass?.simpleName}")
                                    afterMove(newId, calendar, type, entry.startMillis, entry.allDay)
                                } else {
                                    null
                                }
                            }
                        }
                    }
                }
            }
            val failure = outcome.exceptionOrNull()
            val stillEditing = outcome.getOrNull()
            // The user may have opened another entry meanwhile; then the state is theirs.
            val unchanged = editing == current
            when {
                failure != null -> {
                    Log.w("Familienkalender", "save failed: ${failure.javaClass.simpleName}")
                    if (unchanged) message = "Speichern fehlgeschlagen. Bitte später noch einmal versuchen."
                }
                !unchanged -> {}
                stillEditing != null -> {
                    editing = stillEditing
                    message = "Neuer Eintrag angelegt, der alte konnte nicht gelöscht werden."
                }
                else -> startNew("Gespeichert.")
            }
            saving = false
        }
    }

    fun remove() {
        val current = editing ?: return
        val old = calendars.find { it.id == current.calendarId }
        if (old == null) {
            message = "Diesen Eintrag gibt es nicht mehr."
            return
        }
        if (onDeletePress(confirmDelete) == DeleteAction.ASK) {
            confirmDelete = true
            return
        }
        saving = true
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    if (planDelete(current) == DeletePlan.CANCEL_INSTANCE) {
                        store.cancelInstance(old, current.eventId, current.instanceBegin)
                    } else {
                        store.delete(old, current.eventId)
                    }
                }.exceptionOrNull()
            }
            if (failure != null) Log.w("Familienkalender", "delete failed: ${failure.javaClass.simpleName}")
            if (editing == current) {
                if (failure == null) {
                    startNew("Gelöscht.")
                } else {
                    confirmDelete = false
                    message = "Löschen fehlgeschlagen. Bitte später noch einmal versuchen."
                }
            }
            saving = false
        }
    }

    fun fix(action: () -> Unit) {
        scope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching(action).exceptionOrNull() }
            if (failure != null) {
                Log.w("Familienkalender", "fix failed: ${failure.javaClass.simpleName}")
                message = "Das hat nicht geklappt. Bitte in der Google-Kalender-App einschalten."
            }
            changes++
        }
    }

    fun update() {
        updating = true
        scope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { updater.download() }.getOrNull() }
            if (file == null || runCatching { updater.install(file) }.isFailure) {
                message = "Das Update konnte nicht geladen werden. Bitte später noch einmal versuchen."
            }
            updating = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
    ) {
        item(key = "form") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                newVersion?.let { version ->
                    Notice("Version $version ist verfügbar.", MaterialTheme.colorScheme.secondaryContainer) {
                        Button(onClick = ::update, enabled = !updating) {
                            Text(if (updating) "Wird geladen …" else "Aktualisieren")
                        }
                    }
                }

                if (!hasPermission) {
                    Text("Die App braucht Zugriff auf deine Kalender, um Einträge anzulegen.")
                    Button(onClick = { request.launch(PERMISSIONS) }) { Text("Zugriff erlauben") }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                    ) { Text("App-Einstellungen öffnen") }
                    return@Column
                }

                val warning = MaterialTheme.colorScheme.errorContainer
                if (!masterSync) {
                    Notice("Die automatische Synchronisierung ist ausgeschaltet. Einträge erreichen Google so womöglich erst später.", warning) {
                        Button(onClick = { context.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) }) {
                            Text("Einstellungen öffnen")
                        }
                    }
                }
                problems.forEach { check ->
                    Notice(hint(check), warning) {
                        val calendar = check.calendar
                        when {
                            check.status == CalendarStatus.SYNC_OFF && calendar != null ->
                                Button(onClick = { fix { store.enableSync(calendar) } }) { Text("Einschalten") }
                            check.status == CalendarStatus.MISSING || check.status == CalendarStatus.READ_ONLY -> {
                                Button(onClick = { fix { store.refresh() } }) { Text("Erneut prüfen") }
                                context.packageManager.getLaunchIntentForPackage(GOOGLE_CALENDAR)?.let { open ->
                                    OutlinedButton(onClick = { context.startActivity(open) }) { Text("Google Kalender") }
                                }
                            }
                        }
                    }
                }

                if (editing != null) {
                    Text("Eintrag bearbeiten", style = MaterialTheme.typography.titleLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EntryType.entries.forEach { type ->
                        FilterChip(
                            selected = form.type == type,
                            onClick = {
                                change(form.copy(type = type))
                                message = ""
                            },
                            label = { Text(type.label) },
                        )
                    }
                }

                OutlinedTextField(
                    value = form.text,
                    onValueChange = { change(form.copy(text = it)) },
                    label = { Text(if (form.type == EntryType.BIRTHDAY) "Name" else "Text") },
                    modifier = Modifier.fillMaxWidth(),
                )

                val appointment = form.type == EntryType.APPOINTMENT
                val timed = form.type != EntryType.BIRTHDAY && !(appointment && form.allDay)

                if (appointment) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = form.allDay, onCheckedChange = { change(followStart(form.copy(allDay = it))) })
                        Text("Ganztägig")
                    }
                    Text("Von")
                }
                DateTimeRow(
                    date = form.startDate,
                    time = form.startTime.takeIf { timed },
                    onDate = { change(followStart(form.copy(startDate = it))) },
                    onTime = { change(followStart(form.copy(startTime = it))) },
                )
                if (appointment) {
                    Text("Bis")
                    DateTimeRow(
                        date = form.endDate,
                        time = form.endTime.takeIf { timed },
                        onDate = { change(form.copy(endDate = it)) },
                        onTime = { change(form.copy(endTime = it)) },
                    )
                }

                // A same-type edit always writes to the entry's own calendar, so there is nothing to pick.
                if (calendars.isNotEmpty() && editing?.type != form.type) {
                    CalendarPicker(target, calendars) {
                        target = it
                        prefs.edit().putLong(form.type.name, it.id).apply()
                        picks++
                    }
                }

                Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
                if (editing != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = ::remove,
                            enabled = !saving,
                            colors = if (confirmDelete) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (confirmDelete) "Wirklich löschen?" else "Löschen") }
                        OutlinedButton(onClick = { startNew("") }, enabled = !saving, modifier = Modifier.weight(1f)) { Text("Abbrechen") }
                    }
                }
                if (message.isNotEmpty()) Text(message)

                if (loaded) {
                    Text(
                        if (agenda.isEmpty()) "Keine kommenden Einträge." else "Nächste Einträge",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        items(visible, key = { it.key }) { item ->
            AgendaRow(item, onClick = if (!saving && editableType(item.instance, targets) != null) ({ openEntry(item) }) else null)
        }
    }
}

@Composable
private fun Notice(text: String, color: Color, actions: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
private fun AgendaRow(item: AgendaItem, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val tone = when (item.tone) {
        Tone.NOW -> colors.error
        Tone.NEXT -> if (isSystemInDarkTheme()) Color(0xFF66FF66) else Color(0xFF2E7D32)
        Tone.PLAIN -> colors.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(item.type.icon), contentDescription = item.type.label, tint = tone, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(item.day, style = MaterialTheme.typography.labelMedium, color = tone)
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (item.right.isNotEmpty()) {
            Text(item.right, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

@Composable
private fun DateTimeRow(date: LocalDate, time: LocalTime?, onDate: (LocalDate) -> Unit, onTime: (LocalTime) -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickDate(context, date, onDate) }) { Text(date.format(DATE_TEXT)) }
        if (time != null) {
            OutlinedButton(onClick = { pickTime(context, time, onTime) }) { Text(time.format(TIME_TEXT)) }
        }
    }
}

@Composable
private fun CalendarPicker(target: DeviceCalendar?, calendars: List<DeviceCalendar>, onPick: (DeviceCalendar) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(target?.let { "Kalender: ${it.name}" } ?: "Kalender wählen") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            calendars.forEach { calendar ->
                DropdownMenuItem(
                    text = { Text("${calendar.name} (${calendar.accountName})") },
                    onClick = {
                        onPick(calendar)
                        open = false
                    },
                )
            }
        }
    }
}
