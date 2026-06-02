# CarLyrics

Sideloaded Android Auto app that shows synced lyrics on your head unit while
any music app plays in the background. Lyrics are also pushed to the HUD by
simulating a navigation trip, so the current line is visible without looking
down at the center stack.

> Personal-use project. Not Play-Store-bound. Host validation is set to
> `ALLOW_ALL_HOSTS_VALIDATOR` — tighten before any public distribution.

## Features

- Reads the currently-playing track from any music app via a
  `NotificationListenerService` + active `MediaSession` enumeration.
- Looks lyrics up against [LRCLIB](https://lrclib.net/) with multiple fallback
  queries, caches them locally, and prefers synced timing when available.
- Renders the current line large on the `NavigationTemplate` map surface, with
  album-tinted gradient text, song/artist footer, progress bar, and a saved-
  to-device indicator.
- Publishes the same current line to the head-unit **HUD** by driving a fake
  navigation trip through `NavigationManager`. Can be toggled off from the
  in-car menu if you want Google Maps / Waze to own the HUD instead.
- Light/dark mode toggle for the lyrics surface.

## Project layout

```
app/src/main/java/com/carlyrics/
├── car/                       # Android Auto / Car App Library side
│   ├── LyricsCarAppService.kt # CarAppService, NAVIGATION role
│   ├── LyricsSession.kt       # Per-connection session, owns HudTripPublisher
│   ├── LyricsScreen.kt        # NavigationTemplate + ActionStrip
│   ├── LyricsSurfaceCallback.kt # Draws lyrics onto the map surface
│   ├── LyricsMenuScreen.kt    # In-car settings (light mode, HUD toggle, reset)
│   ├── LyricsDisplaySettings.kt # In-memory settings singleton
│   └── HudTripPublisher.kt    # Fake-trip publisher for the HUD
├── lyrics/                    # LRCLIB client, cache, parsed lyrics state
│   ├── LrcLibClient.kt
│   ├── LyricsCache.kt
│   ├── LyricsState.kt
│   └── CurrentLyric.kt        # Shared "current line" derivation
├── media/
│   ├── MediaMonitorService.kt # NotificationListenerService
│   └── MediaState.kt          # Process-wide hand-off to the car surface
├── MainActivity.kt            # Phone-side launcher placeholder
└── AppReset.kt
```

## Build

Requirements:

- Android Studio with AGP 9.2.x (built-in Kotlin support — do **not** apply
  `org.jetbrains.kotlin.android` separately)
- JDK 11+ (Android Studio's bundled JBR works)
- `androidx.car.app:app:1.7.0`
- `minSdk 24`, `compileSdk 36`

From the project root:

```sh
./gradlew :app:assembleDebug
```

On Windows PowerShell, point Gradle at the bundled JBR first:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

Install on the phone:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-time setup on the phone

1. Install the debug APK as above.
2. **Grant notification-listener access** — Settings → Notifications →
   Notification access → CarLyrics. Without this, `MediaMonitorService` cannot
   observe playing tracks.
3. Connect to Android Auto (wired or wireless). CarLyrics appears under the
   navigation category on the head unit; launch it.

There is currently no phone-side onboarding UI (Step 2 of the build order is
intentionally skipped). The Android Studio "Default Activity not found" run-
config error is resolved by setting **Run → Launch: Nothing**.

## HUD navigation toggle

The app pushes a fake `Trip` to the host so the current lyric line appears in
the HUD turn-prompt slot. While the trip is active, Android Auto treats
CarLyrics as the routing app — Maps and Waze cannot drive the HUD at the same
time.

To hand the HUD back to a real navigation app:

1. On the lyrics screen, tap the `⋮` icon in the action strip.
2. Toggle **HUD navigation** off.

This calls `NavigationManager.navigationEnded()` immediately, so the host can
reassign the slot without bouncing the session. Toggle it back on whenever you
want lyrics on the HUD again. (The toggle is in-memory only and resets to
enabled on each AA reconnect.)

## Permissions

| Permission | Reason |
| --- | --- |
| `android.permission.INTERNET` | LRCLIB lookups |
| `androidx.car.app.NAVIGATION_TEMPLATES` | Use of `NavigationTemplate` |
| `androidx.car.app.MAP_TEMPLATES` | Map surface access |
| `androidx.car.app.ACCESS_SURFACE` | Drawing to the map surface — without this the host throws `SecurityException` |
| `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` (declared on the service) | Lets the system bind `MediaMonitorService` once the user grants notification access |

## Lyrics source

Lyrics are fetched from [LRCLIB](https://lrclib.net/). The client tries the
most specific lookup first (title + artist + album + duration) and falls back
to looser queries when nothing matches. Mismatched results (duration off by
too much, fuzzy title mismatch) are rejected rather than displayed.

## License

No license declared — personal sideload project. Don't redistribute without
asking.
