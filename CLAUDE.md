# HomeDash

Android wall display for a 2024 Onn Pro 11" tablet (1840x1280 landscape). Always dark theme, screen kept on,
system bars hidden (swipe to show). One screen: week calendar on top; clock + weather, dinners, and blood sugar
in the middle; quick-action buttons and a settings gear at the bottom.

Open items and unverified behavior live in `TODO.md`. Update it when something gets verified or deferred.

## Stack and build
- Kotlin 2.2, Jetpack Compose (BOM 2026.02.01), Material 3, AGP 9.5. minSdk 26, targetSdk/compileSdk 37, Java 11.
- Package `com.example.homedash`. The folder is still named `HomeTabletApp`. Not a git repo.
- Build: `./gradlew :app:assembleDebug`. Install: `./gradlew :app:installDebug`, or
  `adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk`.
- adb isn't on PATH: `~/Library/Android/sdk/platform-tools/adb`. macOS has no `timeout` command.
- No network libraries: HTTP is plain `HttpURLConnection` with 10 s timeouts, and JSON is `org.json`.
  Other dependencies: DataStore Preferences, biweekly (ICS parsing), material-icons-core.

## Code map (`app/src/main/java/com/example/homedash/`)
- `MainActivity.kt`: keep-screen-on, hides the system bars, sets `HomeDashTheme { DashboardScreen() }`.
- `ui/DashboardScreen.kt`: the whole screen. It holds every refresh loop, the card composables (`WeekCalendar`,
  `LeftPanel`, `DinnerPanel`, `GlucosePanel`, `QuickActionsRow`), and the `openUrl` helper.
- `ui/SettingsDialog.kt`: the settings pop-up. It has one section per service with a Test connection button where
  it makes sense, plus "Refresh all now". Only Save or Cancel close it (`dismissOnClickOutside = false`).
- `data/SettingsStore.kt`: a single DataStore (`settings`) behind the `Settings` data class. Calendars are stored
  as a JSON array.
- `data/IcsClient.kt`: fetches ICS calendars and expands recurring events (biweekly) for a date range.
- `data/WeatherClient.kt`: Open-Meteo current conditions and ZIP geocoding, in °F, with no API key.
- `data/LocationProvider.kt`: coarse network location (20 s timeout, then last known) and a Geocoder city name.
- `data/NorishClient.kt`: the Norish week of planned dinners.
- `data/DexcomClient.kt`: the latest Dexcom Share reading.
- `ui/theme/`: the template theme, forced dark with dynamic color off.

## Patterns to follow
- **Refresh loops:** each card is `var x by remember { mutableStateOf(...) }` plus a
  `LaunchedEffect(<its settings>, refreshAll) { while (true) { ... delay(...) } }`.
  - Don't use `produceState`. It resets to its initial value whenever a key changes, so a refresh would blank
    the card.
  - Regular intervals: calendar 15 min, weather 30 min, dinners 10 min, Dexcom 5 min. After a failure, wait
    `RETRY_DELAY` (1 min) instead.
  - Keep the last good data when a fetch fails.
  - `refreshAll` is bumped by Settings' "Refresh all now" and is a key on every loop. There are no per-card
    refresh buttons.
- **Errors:** rethrow `CancellationException`, since settings changes cancel loops. Log failures with
  `Log.w` under the service's tag: `Weather`, `Calendar`, `Norish`, or `Dexcom`.
- **New service checklist:** add a field to `Settings` and a key in `SettingsStore`, a section in
  `SettingsDialog` (with Test connection returning a one-line result string), a loop plus a card in
  `DashboardScreen`, and a "Set up X in settings" empty state.
- **Links and other apps:** go through `openUrl` (it catches `ActivityNotFoundException`). Opening another app
  needs a `<queries>` entry in `AndroidManifest.xml` (Android 11+ package visibility).
- **Outside APIs:** when code depends on one, add a comment naming the doc or source file it came from.

## Services and gotchas
- **Calendars (ICS):** the Google "secret iCal address", iCloud shared calendars, and so on.
  - `webcal://` and `webcals://` are saved as `https://`.
  - `http://` is rejected so the secret URLs are never sent unencrypted.
  - Multi-day events only show on their start day.
- **Weather:**
  - Location comes from Wi-Fi (coarse) first, then the ZIP code in Settings.
  - The card shows only the city name.
  - Tapping the card opens Breezy Weather (`org.breezyweather`) if it's installed, else the NWS forecast page
    (`forecast.weather.gov/MapClick.php?lat=&lon=`).
  - Breezy's data-sharing provider was tried and removed at the user's request. Don't bring it back unasked.
- **Norish (self-hosted dinners):** `GET {server}/api/v1/planned-recipes/week` with the `x-api-key` header.
  - The server must be **v0.18.0 or newer**. Older versions return a Next.js 404.
  - The server needs **`TZ=America/Chicago`** on its container. Norish computes the Mon–Sun week from its Node
    clock, so on UTC the week rolls over Sunday evening and the panel goes blank.
  - Only `slot == "Dinner"` is shown, ordered by `sortOrder`.
  - Tapping a dinner opens `{server}/recipes/{recipeId}`.
  - The server is on the LAN over plain HTTP (cleartext is allowed in the manifest).
  - The OpenAPI spec is `apps/docs/docs/reference/openapi.json` in `norish-recipes/norish`.
- **Dexcom (blood sugar):** the unofficial Share API, ported from pydexcom (`gagebenne/pydexcom`: `const.py`,
  `dexcom.py`).
  - US server only.
  - Uses the credentials of the account that shares readings, not a follower's. Share must be on with at least
    one follower.
  - A rejected login (`DexcomAuthException`) must **stop the loop** until the settings change. Retrying can lock
    the Dexcom account.
  - The session is reused and renewed only when Dexcom reports it expired.
  - Color bands: red below 70, orange above 180, and flagged as stale after 15 min.
- **Quick actions (Lights, Lock up, Movie mode, Goodnight):** placeholders. The Google Home plan is in `TODO.md`.

## Secrets
- Calendar URLs, the Norish API key, and the Dexcom password are secret.
  - They're stored only in the app's DataStore on the device, unencrypted.
  - Never put them in source, logs, `TODO.md`, or anything shared. The calendar logs use the calendar's name,
    never its URL.
- `local.properties` holds only `sdk.dir`.
- The `Log.d("Norish", body)` raw-response log is still in place. Remove it before any release build.

## Running and testing
- **Emulator:** AVD `Onn_Tablet_1920x1200` (`emulator-5556`).
  - It has no network location, so weather needs a ZIP code there.
  - System "not responding" dialogs often swallow taps, so take a screenshot before trusting an
    `adb shell input tap`.
  - Prefer `adb exec-out screencap -p` over `uiautomator dump`, which can return a stale screen.
- **Mock servers:** for Norish-style APIs, run a small Python server on the Mac, then
  `adb reverse tcp:8765 tcp:8765` and point the app at `http://127.0.0.1:8765`. Traffic to `10.0.2.2` timed out.
  Remove the reverse and stop the server afterward.
- **Real tablet:** connect over wireless debugging.
  - The port changes whenever the tablet sleeps, and the connection drops often.
  - The user runs `adb connect <ip>:<port>` from their own Terminal: adb started from Claude's shell gets
    "No route to host" because of macOS Local Network permission.
  - Check `adb devices` before installing. If the tablet is missing, ask the user to reconnect it rather than
    retrying.
  - The user may prefer to install with Run in Android Studio, so ask before pushing builds to the tablet.
- **Debug-build files:** `run-as com.example.homedash` can read the app's files on the tablet. Never print
  secret values from them.
- **Shell scripts:** zsh word-splitting has broken one-liners here, so put anything non-trivial in a bash script
  in the scratchpad.

## Working rules for this repo
- Before editing, explain the change, wait for approval, and ask whether the user or Claude should make it. This
  follows the global CLAUDE.md.
- Never remove or reword existing comments unless the code they describe is removed.
- After a change, build. If a device is available and the user agrees, install and check it with a screenshot.
- Report what was verified and what wasn't (emulator vs. real tablet vs. only compiled).
