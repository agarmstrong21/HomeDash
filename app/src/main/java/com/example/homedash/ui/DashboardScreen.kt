package com.example.homedash.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.homedash.data.CurrentWeather
import com.example.homedash.data.DexcomAuthException
import com.example.homedash.data.DexcomClient
import com.example.homedash.data.Dinner
import com.example.homedash.data.GlucoseReading
import com.example.homedash.data.IcsClient
import com.example.homedash.data.LocationProvider
import com.example.homedash.data.NorishClient
import com.example.homedash.data.Place
import com.example.homedash.data.Settings
import com.example.homedash.data.SettingsStore
import com.example.homedash.data.WeatherClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ---------- Models (replace placeholder data with real sources later) ----------
data class CalEvent(val title: String, val time: String)
data class QuickAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

// How long the refresh loops wait before retrying after a failed update (Wi-Fi blip, server restart, slow location fix).
private const val RETRY_DELAY = 60_000L

// Breezy Weather, opened when the weather card is tapped. Must match the <queries> entry in AndroidManifest.xml.
private const val BREEZY_PACKAGE = "org.breezyweather"

// Google Calendar, opened when a day on the week calendar is tapped. Also listed in <queries>.
private const val GOOGLE_CALENDAR_PACKAGE = "com.google.android.calendar"

// Monday through Sunday of the week containing today, matching Norish's week endpoint.
private fun weekDays(today: LocalDate): List<LocalDate> {
    val monday = today.with(DayOfWeek.MONDAY)
    return (0..6).map { monday.plusDays(it.toLong()) }
}

// ---------- Screen ----------
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context.applicationContext) }
    val settings by settingsStore.settings.collectAsState(initial = Settings())
    val calendars = settings.calendars
    val zipCode = settings.zipCode
    val norishUrl = settings.norishUrl
    val norishApiKey = settings.norishApiKey
    val dexcomUsername = settings.dexcomUsername
    val dexcomPassword = settings.dexcomPassword
    var showSettings by remember { mutableStateOf(false) }
    // "Refresh all now" in settings bumps this. Every refresh loop below is keyed on it, so each one restarts:
    // fetch now, then its regular timer starts over.
    var refreshAll by remember { mutableIntStateOf(0) }

    // Coarse location is optional: without it the weather falls back to the ZIP code in settings.
    val locationProvider = remember { LocationProvider(context.applicationContext) }
    var locationGranted by remember { mutableStateOf(locationProvider.hasPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationGranted = granted
    }
    LaunchedEffect(Unit) {
        if (!locationGranted) permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val now by produceState(LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); delay(15_000) }
    }
    // Derived from the clock so the week and "today" roll over at midnight.
    val today = now.toLocalDate()
    val weekStart = today.with(DayOfWeek.MONDAY)
    // Events from every saved calendar for this week. Refreshed every 15 minutes and when the calendars or week change.
    // The calendar, weather, and blood sugar loops use plain state + LaunchedEffect (like dinners below) so a
    // refresh keeps showing the last result instead of blanking the card.
    var events by remember { mutableStateOf(emptyMap<LocalDate, List<CalEvent>>()) }
    var eventsLoading by remember { mutableStateOf(false) }
    LaunchedEffect(calendars, weekStart, refreshAll) {
        val ics = IcsClient()
        while (true) {
            var failed = false
            val fetched = withLoading({ eventsLoading = it }) {
                calendars.flatMap { source ->
                    runCatching { ics.eventsBetween(source.url, weekStart, weekStart.plusDays(6)) }
                        // Logs the calendar's name only; its URL is secret.
                        .onFailure { if (it is CancellationException) throw it; failed = true; Log.w("Calendar", "Couldn't load \"${source.name}\": $it") }
                        .getOrDefault(emptyList())
                }
            }
            events = fetched.groupBy { it.date }.mapValues { (_, list) ->
                list.sortedBy { it.start ?: LocalTime.MIN }.map { CalEvent(it.title, it.time) }
            }
            // After a failure, try again in a minute instead of waiting for the next regular refresh.
            delay(if (failed) RETRY_DELAY else 15 * 60_000L)
        }
    }
    // Weather from the tablet's Wi-Fi location, or the settings ZIP code if that isn't available. Refreshed every 30 minutes.
    var weather by remember { mutableStateOf<CurrentWeather?>(null) }
    var weatherLoading by remember { mutableStateOf(false) }
    LaunchedEffect(zipCode, locationGranted, refreshAll) {
        val client = WeatherClient()
        while (true) {
            val ok = withLoading({ weatherLoading = it }) { runCatching {
                val here = if (locationGranted) locationProvider.currentLocation() else null
                // The name is the city shown on the card; blank if the device's geocoder can't name it.
                val place = if (here != null) {
                    Place(here.latitude, here.longitude, locationProvider.cityName(here).orEmpty())
                } else {
                    zipCode.takeIf { it.length == 5 }?.let { client.lookupZip(it) }
                }
                place ?: error("No location fix and no usable ZIP code")
                weather = client.current(place.latitude, place.longitude)
                    .copy(location = place.name, latitude = place.latitude, longitude = place.longitude)
            }.onFailure { if (it !is CancellationException) Log.w("Weather", "Update failed: $it") }.isSuccess }
            // After a failure, try again in a minute instead of waiting for the next regular refresh.
            delay(if (ok) 30 * 60_000L else RETRY_DELAY)
        }
    }
    // Dinners from Norish, refreshed every 10 minutes. Falls back to the last good result.
    // Restarts when the Norish settings change; nothing is fetched until a server URL is set.
    // Plain state + LaunchedEffect rather than produceState, which would reset to empty on every restart.
    var dinners by remember { mutableStateOf(emptyMap<LocalDate, List<Dinner>>()) }
    var dinnersLoading by remember { mutableStateOf(false) }
    LaunchedEffect(norishUrl, norishApiKey, refreshAll) {
        if (norishUrl.isBlank()) { dinners = emptyMap(); return@LaunchedEffect }
        val client = NorishClient(norishUrl, norishApiKey)
        while (true) {
            val ok = withLoading({ dinnersLoading = it }) {
                runCatching { client.weekDinners() }.onSuccess { dinners = it }
                    .onFailure { if (it !is CancellationException) Log.w("Norish", "Update failed: $it") }.isSuccess
            }
            // After a failure, try again in a minute instead of waiting for the next regular refresh.
            delay(if (ok) 10 * 60_000L else RETRY_DELAY)
        }
    }
    // Latest blood sugar from Dexcom Share, every 5 minutes (Dexcom records a reading every 5 minutes).
    // Nothing is fetched until a username and password are saved.
    var glucose by remember { mutableStateOf<GlucoseReading?>(null) }
    var glucoseLoaded by remember { mutableStateOf(false) }
    var glucoseError by remember { mutableStateOf<String?>(null) }
    var glucoseLoading by remember { mutableStateOf(false) }
    LaunchedEffect(dexcomUsername, dexcomPassword, refreshAll) {
        if (dexcomUsername.isBlank() || dexcomPassword.isBlank()) {
            glucose = null; glucoseLoaded = false; glucoseError = null
            return@LaunchedEffect
        }
        val client = DexcomClient(dexcomUsername, dexcomPassword)
        while (true) {
            val error = withLoading({ glucoseLoading = it }) {
                try {
                    glucose = client.latest()
                    glucoseLoaded = true
                    glucoseError = null
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
            }
            if (error is DexcomAuthException) {
                // Stop here: retrying a rejected login can lock the Dexcom account. Saving new settings
                // (or Refresh all) starts this loop again.
                glucoseError = "Login failed: ${error.message}"
                Log.w("Dexcom", "Login failed, not retrying until settings change: ${error.message}")
                return@LaunchedEffect
            }
            if (error != null) Log.w("Dexcom", "Update failed: $error")
            // After a failure, try again in a minute instead of waiting for the next regular refresh.
            delay(if (error == null) 5 * 60_000L else RETRY_DELAY)
        }
    }
    // Opens a page in the tablet's browser (or an app that handles the link), shared by the dinner and weather cards.
    val openUrl: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Couldn't open $url", Toast.LENGTH_SHORT).show()
        }
    }
    // Opens the calendar app at a day, using the Calendar Provider's time URI
    // (https://developer.android.com/identity/providers/calendar-provider#intents). It sets only the date; the
    // app keeps whichever view was used last, so Week view lands on the tapped week.
    // Google Calendar first, then any calendar app.
    val openCalendarDay: (LocalDate) -> Unit = { day ->
        val millis = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/$millis"))
        try {
            context.startActivity(Intent(intent).setPackage(GOOGLE_CALENDAR_PACKAGE))
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // Opens the recipe's page on the Norish server in the tablet's browser (or the Norish web app, if installed).
    val openRecipe: (Dinner) -> Unit = { dinner ->
        openUrl("${norishUrl.trimEnd('/')}/recipes/${dinner.recipeId}")
    }
    // National Weather Service forecast for the weather card's location: 7-day and hourly forecast, alerts, no ads.
    // Opens Breezy Weather when it's installed; otherwise the NWS page below.
    val openForecast: (CurrentWeather) -> Unit = { w ->
        val breezyApp = context.packageManager.getLaunchIntentForPackage(BREEZY_PACKAGE)
        if (breezyApp != null) context.startActivity(breezyApp)
        else openUrl(String.format(Locale.US, "https://forecast.weather.gov/MapClick.php?lat=%.4f&lon=%.4f", w.latitude, w.longitude))
    }
    val actions = listOf(
        QuickAction("Lights", Icons.Default.Home) { /* TODO Google Home */ },
        QuickAction("Lock up", Icons.Default.Lock) { /* TODO */ },
        QuickAction("Movie mode", Icons.Default.PlayArrow) { /* TODO */ },
        QuickAction("Goodnight", Icons.Default.Star) { /* TODO */ },
    )

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WeekCalendar(events, today, loading = eventsLoading, onDayClick = openCalendarDay, modifier = Modifier.fillMaxWidth().weight(0.6f))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LeftPanel(now, weather, weatherLoading = weatherLoading, onWeatherClick = openForecast, modifier = Modifier.width(280.dp).fillMaxHeight())
                DinnerPanel(
                    dinners, today, configured = norishUrl.isNotBlank(),
                    loading = dinnersLoading,
                    onOpen = openRecipe,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                GlucosePanel(
                    glucose,
                    configured = dexcomUsername.isNotBlank() && dexcomPassword.isNotBlank(),
                    loaded = glucoseLoaded,
                    loading = glucoseLoading,
                    error = glucoseError,
                    now = now,
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                )
            }
            QuickActionsRow(actions, onSettings = { showSettings = true })
        }
    }

    if (showSettings) {
        SettingsDialog(
            initial = settings,
            onSave = { updated ->
                scope.launch { settingsStore.save(updated) }
                showSettings = false
            },
            onDismiss = { showSettings = false },
            onRefreshAll = { refreshAll++ },
        )
    }
}

@Composable
private fun DashCard(modifier: Modifier, loading: Boolean = false, content: @Composable ColumnScope.() -> Unit) =
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box {
            Column(Modifier.padding(20.dp), content = content)
            // Small spinner in the top-right corner while the card is fetching; it doesn't move the content.
            if (loading) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.TopEnd).padding(20.dp).size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }

// Sets the loading flag while [block] runs (and clears it even if it throws or is cancelled), so the card shows
// its spinner during the fetch.
private suspend fun <T> withLoading(setLoading: (Boolean) -> Unit, block: suspend () -> T): T {
    setLoading(true)
    try {
        return block()
    } finally {
        setLoading(false)
    }
}

@Composable
private fun LeftPanel(
    now: LocalDateTime,
    w: CurrentWeather?,
    weatherLoading: Boolean,
    onWeatherClick: (CurrentWeather) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashCard(Modifier.fillMaxWidth().weight(1f)) {
            Text(now.format(DateTimeFormatter.ofPattern("h:mm")), fontSize = 72.sp,
                style = MaterialTheme.typography.displayLarge)
            Text(now.format(DateTimeFormatter.ofPattern("EEEE")),
                style = MaterialTheme.typography.headlineMedium)
            Text(now.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Tappable only once there's weather with a location to link to.
        val forecastTarget = w?.takeIf { it.latitude != null && it.longitude != null }
        val cardShape = RoundedCornerShape(28.dp)
        DashCard(
            Modifier.fillMaxWidth().weight(1f).clip(cardShape)
                .then(if (forecastTarget != null) Modifier.clickable { onWeatherClick(forecastTarget) } else Modifier),
            loading = weatherLoading,
        ) {
            if (w == null && weatherLoading) {
                // First fetch still running (a location fix can take up to 20 s); don't call it unavailable yet.
                Text("Loading weather…", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (w == null) {
                Text("Weather unavailable", style = MaterialTheme.typography.titleMedium)
                Text("Check Wi-Fi or set a ZIP code in settings",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${w.tempF}°", style = MaterialTheme.typography.displayMedium)
                Text(w.summary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("H ${w.high}°  L ${w.low}°",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (w.location.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(w.location, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WeekCalendar(
    events: Map<LocalDate, List<CalEvent>>,
    today: LocalDate,
    loading: Boolean,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    DashCard(modifier, loading = loading) {
        Text("This week", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            weekDays(today).forEach { day ->
                val isToday = day == today
                // The whole tile (events included) is one tap target; the ripple is clipped to its shape.
                Surface(
                    onClick = { onDayClick(day) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isToday) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            style = MaterialTheme.typography.labelLarge)
                        Text("${day.dayOfMonth}", style = MaterialTheme.typography.headlineSmall)
                        events[day].orEmpty().forEach { e ->
                            Surface(shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Column(Modifier.padding(6.dp)) {
                                    Text(e.title, style = MaterialTheme.typography.labelLarge)
                                    Text(e.time, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DinnerPanel(
    dinners: Map<LocalDate, List<Dinner>>,
    today: LocalDate,
    configured: Boolean,
    loading: Boolean,
    onOpen: (Dinner) -> Unit,
    modifier: Modifier,
) {
    DashCard(modifier, loading = loading) {
        Text("Dinner", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (!configured) {
            Text("Set up Norish in settings", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                weekDays(today).forEach { day ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            Modifier.width(48.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val planned = dinners[day].orEmpty()
                        if (planned.isEmpty()) {
                            Text("—", style = MaterialTheme.typography.titleMedium)
                        } else {
                            // Each dinner is its own tap target, so two on one day open different recipes.
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                planned.forEach { dinner ->
                                    val link = dinner.recipeId != null
                                    Text(
                                        dinner.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (link) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                        modifier = if (link) Modifier.clickable { onOpen(dinner) } else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Below 70 mg/dL shows red and above 180 orange; in between uses the normal text color.
private const val GLUCOSE_LOW = 70
private const val GLUCOSE_HIGH = 180
private val GlucoseHighColor = Color(0xFFFFB74D)

// Readings older than this are flagged, since Dexcom normally sends one every 5 minutes.
private const val GLUCOSE_STALE_MINUTES = 15

@Composable
private fun GlucosePanel(
    reading: GlucoseReading?,
    configured: Boolean,
    loaded: Boolean,
    loading: Boolean,
    error: String?,
    now: LocalDateTime, // Passed so "x min ago" updates with the clock tick.
    modifier: Modifier,
) {
    DashCard(modifier, loading = loading) {
        Text("Blood sugar", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when {
            !configured -> Text("Set up Dexcom in settings", style = MaterialTheme.typography.titleMedium, color = muted)
            error != null -> Text(error, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            !loaded -> Text("Loading…", style = MaterialTheme.typography.titleMedium, color = muted)
            reading == null -> Text("No readings in the last 24 hours", style = MaterialTheme.typography.titleMedium, color = muted)
            else -> {
                val color = when {
                    reading.mgdl < GLUCOSE_LOW -> MaterialTheme.colorScheme.error
                    reading.mgdl > GLUCOSE_HIGH -> GlucoseHighColor
                    else -> Color.Unspecified
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${reading.mgdl}", style = MaterialTheme.typography.displayLarge, color = color)
                    Text(reading.arrow, style = MaterialTheme.typography.displaySmall, color = color,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                Text("mg/dL", style = MaterialTheme.typography.titleMedium, color = muted)
                Spacer(Modifier.height(8.dp))
                val minutes = reading.minutesAgo()
                Text(
                    when {
                        minutes == 0L -> "Just now"
                        minutes < 60 -> "$minutes min ago"
                        else -> "${minutes / 60} h ${minutes % 60} min ago"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                )
                if (minutes > GLUCOSE_STALE_MINUTES) {
                    Text("No recent reading", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun QuickActionsRow(actions: List<QuickAction>, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        actions.forEach { a ->
            FilledTonalButton(
                onClick = a.onClick,
                modifier = Modifier.weight(1f).height(72.dp),
            ) {
                Icon(a.icon, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(a.label, style = MaterialTheme.typography.titleMedium)
            }
        }
        FilledTonalButton(
            onClick = onSettings,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Calendar settings")
        }
    }
}
