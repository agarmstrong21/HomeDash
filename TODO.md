# HomeDash TODO

## Google Home: "Office lights" button (tabled 2026-10-29)

Goal: the first quick-action button turns off every light in the Google Home room named **Office**.
The Home APIs can't run routines made in the Google Home app (an app only sees automations it
created), so this uses direct device control. Later buttons ("Goodnight", "Movie mode") can be
recreated as HomeDash-owned automations with a manual starter.

### Setup (use a personal Google account, not the work account)
- [ ] Sign in at https://developers.home.google.com/apis/android/sdk and download the Android Home APIs SDK.
      It isn't on Maven, so it has to be hosted locally in the project. Note where it was saved.
- [ ] Google Cloud console: create a project, then **APIs & Services > OAuth consent screen**:
      External, fill in app info, **add no scopes**, add your Gmail under **Audience** as a test user.
- [ ] **Credentials > Create OAuth client**, type **Android**:
  - Package name: `com.example.homedash`
  - SHA-1 (debug key on this Mac, `~/.android/debug.keystore`): `70:FC:6D:65:1B:12:20:A6:C0:81:71:F9:57:84:AF:73:4A:5A:12:94`
- [ ] Google Home app: a room named **Office** containing the lights.
- [ ] Tablet: signed in with the same personal Google account, on Wi-Fi, with Google Play services.
- Google Home Developer Console registration is not required for testing, only for publishing.

### Code changes (once the SDK is downloaded)
- [ ] `build.gradle.kts` / `libs.versions.toml`: add the SDK from the local folder. May need `minSdk` 26 -> 29
      (docs list Android 10+ for test devices); confirm from the SDK.
- [ ] New `data/HomeController.kt`: `FactoryRegistry` (light device types + OnOff trait), `HomeConfig`,
      `Home.getClient`; `hasPermissions()` / `requestPermissions()`; `turnOffRoom("Office")` that finds the
      structure, the room (case-insensitive), its lights, and sends off to each.
- [ ] `MainActivity.kt`: call `registerActivityResultCallerForPermissions(this)` in `onCreate`.
- [ ] `DashboardScreen.kt`: rename the first button to "Office lights"; first tap shows Google's permission
      screen, then it turns the lights off and shows a short result ("Turned off 3 lights" or the error).
- [ ] Confirm against the downloaded SDK: the call for listing a room's devices and the exact OnOff off command
      (the docs pages checked didn't show them).
- Test on the real tablet; the emulator can't run the Home APIs.

Docs: [SDK](https://developers.home.google.com/apis/android/sdk) ·
[OAuth](https://developers.home.google.com/apis/android/oauth) ·
[Initialize](https://developers.home.google.com/apis/android/initialize) ·
[Permissions](https://developers.home.google.com/apis/android/permissions) ·
[Structure](https://developers.home.google.com/apis/android/structure) ·
[Automations](https://developers.home.google.com/apis/android/automation/build)

## Tapo camera live view (researched 2026-11-02, not started)

Goal: show a Tapo camera's live view on HomeDash over the local network (no cloud, no Tapo app).

### How it would work
- Plug-in Tapo cameras expose RTSP at `rtsp://<camera-ip>:554/stream1` (full quality) or `/stream2` (lower
  quality, better for a dashboard). ONVIF (Profile S) is also available on port 2020.
- The stream needs a **camera account**, which is separate from the TP-Link login. Create it in the Tapo app under
  the camera's Settings > Advanced Settings > Camera Account, and avoid special characters in it.
- Playback would use Media3 ExoPlayer's official RTSP module, `androidx.media3:media3-exoplayer-rtsp`. It plays
  live RTSP with the login in the address (BASIC/DIGEST auth). It adds a few MB to the app.
- Settings would get a Camera section (IP, camera-account username and password, Test button). The password is
  stored only on the tablet, like the Norish key.
- It only works on home Wi-Fi, unless the tablet reaches the camera over Tailscale.

### Limits and risks
- Most battery-powered Tapo cameras don't support RTSP: the C410, C420, C425, and D230 can't. The D225, D235, and
  TD25 can, but only when hardwired and in always-on mode.
- A camera runs only two of these at once: Tapo Care cloud recording, SD-card recording, and an RTSP/ONVIF client
  like HomeDash.
- ExoPlayer's RTSP docs confirm H.264. Some newer Tapo models stream H.265, which wasn't confirmed, so test
  against the real camera first.
- An always-on live tile keeps a stream open all the time and uses Wi-Fi constantly. A tap-to-open view doesn't.

### Before building
- [ ] Find the camera model (label on the camera, or device info in the Tapo app) and check it supports RTSP.
- [ ] Create the camera account in the Tapo app.
- [ ] Decide how it shows: an always-on tile, a button that opens a full-screen live view, or a doorbell-style
      pop-up.
- [ ] Quick test that the stream plays on the tablet (H.264 vs. H.265), before building the full feature.

Docs: [Tapo ONVIF/RTSP FAQ](https://www.omadanetworks.com/us/support/faq/4465/) ·
[Media3 ExoPlayer RTSP](https://developer.android.com/media/media3/exoplayer/rtsp)

## Verification still needed
Everything below was either skipped or only checked on the emulator (`Onn_Tablet_1920x1200`, API 37).

### On the real Onn Pro tablet
- [x] App installs and launches (2026-11-01, over wireless debugging; see "Installing on the tablet" below).
- [ ] Landscape lock, dark theme, and full-screen layout look right at 1920x1200.
- [ ] Screen stays on (no sleep) and system bars stay hidden, coming back only on swipe.
- [ ] Clock rolls over at midnight, and the calendar/dinner week rolls over on Monday (never observed live).
- [ ] Settings pop-up: typing in the fields with the on-screen keyboard works (the emulator dropped
      digits when typing fast), and Save/Cancel stay reachable with the keyboard open.

### Weather
- [ ] First launch shows the location permission prompt (on the emulator it was granted with `adb`, so the
      prompt itself was never seen). Check both Allow and Deny.
- [ ] Wi-Fi location path works (never ran; the emulator has no network location). The card only shows the
      city now, so to tell it apart from the ZIP fallback, clear the ZIP in Settings first. If the city line
      is missing, the tablet has no geocoder.
- [ ] Denying location falls back to the ZIP code; Location turned off in Android settings also falls back.
- [ ] Weather refreshes on its 30-minute cycle.
- [ ] Check the weather-code labels in `WeatherClient.kt` against Open-Meteo's official WMO code table
      (written from memory; the API calls themselves were checked live).

### Calendar
- [ ] A real Google secret iCal address loads in the app (only a fake URL was tried in-app; parsing was
      checked separately on the desktop JVM with a sample feed).
- [ ] Times show in the right time zone, recurring events land on the right days, and deleted single
      occurrences of a recurring event (EXDATE) don't show.
- [ ] biweekly runs on-device without missing-class errors (its POM lists vinnie and Jackson dependencies).
- [ ] Events refresh on the 15-minute cycle and after saving calendar changes.

### Dinners (Norish)
- [x] Real instance works on the real tablet (2026-11-01): the week endpoint, `x-api-key` auth, cleartext HTTP
      to the LAN address, and the server's Mon–Sun week matching the tablet's time zone (after setting `TZ`).
- [ ] Tapping a dinner opens its recipe page (`{server}/recipes/{recipeId}`) in Chrome on the tablet. Only
      checked on the emulator against a mock (Chrome opened; the full path wasn't confirmed).
- [ ] Refresh button on the Dinner card reloads on the tablet (checked on the emulator only).

#### Norish server requirements
- Norish **v0.18.0 or newer**. Older versions have no `/api/v1` API and return a Next.js 404.
- The container needs **`TZ=America/Chicago`** (the tablet's time zone) on the web app service. Norish works
  out "this week" from the Node.js clock, so on UTC it rolls over to Monday at 6 PM Central on Sundays and
  the panel goes blank. Check with
  `docker exec <norish-container> node -e "console.log(new Date().toString(), process.env.TZ)"`.
- Opening a recipe needs a one-time Norish login in Chrome on the tablet; the API key only covers the app.

### Added 2026-11-02 (compiled; not yet run on the tablet unless noted)
- [ ] Blood sugar (Dexcom): Test connection with the real account, then the card shows the reading, arrow, and
      "x min ago". Only the bad-login response was checked (Dexcom returns HTTP 500 `AccountPasswordInvalid`).
- [ ] Settings > Refresh all now refreshes every card without blanking any of them.
- [x] Tapping a calendar day opens Google Calendar on that date. Checked on the tablet by sending the same intent
      with adb: right date, in whatever view Google Calendar was last left in (the view can't be set).
- [ ] An iCloud `webcal://` calendar saves as `https://` and its events show.
- [ ] Tapping the weather card opens Breezy Weather.

### Build
- [ ] Release build (`assembleRelease`) has never been built; it has R8 optimization on, so check DataStore,
      biweekly, and org.json still work after shrinking.
- [ ] Instrumented test (`ExampleInstrumentedTest`, now expecting `com.example.homedash`) has never run.
- [ ] Run Android lint once.

## Other open items
- [ ] Add a real Google Calendar secret iCal address in Settings and confirm events show (work accounts may block it).
- [x] Enter the real Norish server URL (and API key, if needed) in Settings > Meals (Norish), press
      Test connection, then grab the raw response from Logcat (tag "Norish") and tighten the parser in
      `NorishClient.kt` to the real `/api/v1/planned-recipes/week` shape. (Done 2026-11-01: Dinner slot only.)
- [ ] Remove the `Log.d` of the full Norish response before release.

## Installing on the tablet
Wireless debugging drops when the tablet sleeps, and its port changes each time it comes back.
1. Tablet: Settings > System > Developer options > Wireless debugging, on; note the IP address & Port.
2. Mac Terminal: `~/Library/Android/sdk/platform-tools/adb connect 192.168.0.192:PORT`, then Run in Android Studio.
3. "No route to host" means macOS Local Network permission: allow Android Studio and Terminal under
   System Settings > Privacy & Security > Local Network, then `adb kill-server` and connect again from Terminal.
- [ ] Calendar fetch failures are silent; consider showing an error per calendar. Multi-day events only show on their start day.
- [ ] Optional: rename the project folder `HomeTabletApp` -> `HomeDash` (close Android Studio first).
