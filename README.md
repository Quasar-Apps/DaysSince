# Pulsar

A small Android app for tracking the days, hours, and minutes since the
milestones that matter to you — your last night out, the last time you went to the gym, an anniversary,
whatever. Each milestone gets a bold gradient card in the app and an optional
home-screen widget.

- Minimum Android version: **8.0 (API 26)**
- Targets Android: **16 (API 36)**
- Offline-first. No accounts, no permissions, no tracking — your milestones never leave your device.

---

## Features

- **Multiple milestones.** Add as many as you like; each gets its own colour
  accent from the Pulsar palette (Magenta, Violet, Indigo, Nebula, Aurora,
  Solar, Ember, Deep).
- **Detail screen** with a full-bleed gradient hero, count-up animation, and
  a days / hours / minutes breakdown.
- **Two home-screen widgets:**
    - *Days Since* — 1×1 widget showing whole days.
    - *Days · Hours · Minutes* — wide widget with the full breakdown.
- **Per-widget configuration** — when you place a widget, a picker lets you
  bind it to a specific milestone and optionally render it with a transparent
  background (just the number, floating on your wallpaper).
- **Fixed Pulsar brand theme** — the same cosmic-purple identity on every
  device (no wallpaper adaptation), following the system light/dark setting.
- **Accessibility:** TalkBack descriptions on widget content, respects the
  system reduce-motion setting, tabular figures so digits don't jump.
- **No accounts, no permissions, no tracking.** Your milestones are stored in
  app-private DataStore preferences and never leave the device; they're included
  in Auto Backup so they survive a reinstall.

---

## How the elapsed time is calculated

The math lives in
[`app/src/main/java/com/quasarapps/pulsar/ElapsedTime.kt`](app/src/main/java/com/quasarapps/pulsar/ElapsedTime.kt).

- Computes `ZonedDateTime.now() - picked` in the device time zone, so DST
  transitions (spring-forward / fall-back) are accounted for rather than
  always assuming a 24-hour day.
- "Whole days" means the value increments every 24 elapsed hours since the
  picked timestamp. 23h 59m still shows as **0 days**.
- If the picked timestamp is in the future the value clamps to `0d 0h 0m`
  (and the date picker enforces `max = today` to make this hard to trigger).

The behaviour is unit-tested in `ElapsedTimeTest`, including UTC, non-UTC
zones (Pacific/Kiritimati), and both DST transition directions in
America/New_York.

---

## Architecture

```
ui/                         Compose screens + theme
  home/HomeScreen           staggered grid of milestone cards
  detail/DetailScreen       gradient hero + d/h/m breakdown
  edit/EditMilestoneScreen  Material 3 date + time pickers, accent picker
  components/               CountUpNumber, rememberElapsedDhm tick
  theme/                    Pulsar brand colours, type & accent gradients

data/
  Milestone                 id, title, date, time, accent, createdAt
  MilestoneJson             JSON (de)serialisation for the milestone list
  MilestonesRepository      DataStore-backed CRUD + widget bindings

widget/
  WidgetConfigActivity      milestone picker shown when a widget is placed
  glance/DaysWidget         1×1 Glance widget
  glance/DaysHoursMinutes…  wide Glance widget
  glance/WidgetUi           shared Glance scaffold, colours, and click target
  glance/MilestoneGlance…   base receiver that arms/cancels the refresh schedule
  MilestoneWidgets          updateAll() helper called after milestone changes
  WidgetRefreshWorker       periodic worker that re-renders placed widgets
  WidgetRefreshScheduler    schedules/cancels the hourly refresh (WorkManager)

util/EnglishDateFormat      "1st of January 2026" formatting
```

Persistence is a single [Preferences DataStore](app/src/main/java/com/quasarapps/pulsar/data/MilestonesRepository.kt)
named `pulsar_store`, with two keys:

- `milestones_json` — JSON array of `Milestone` objects.
- `widget_bindings_json` — JSON map of `appWidgetId → {milestoneId, transparent}`.

---

## Widgets

Both widgets are written with [Jetpack Glance](https://developer.android.com/develop/ui/compose/glance).
They share:

- The milestone's accent gradient as the widget background, or a fully
  transparent background if the user opts in during configuration.
- A `previewLayout` so the widget picker shows a real-looking preview
  instead of a grey square on Android 12+.
- A `contentDescription` so TalkBack reads e.g.
  *"365 days since Last night out, 1st of January 2026"*.
- A tap target that opens `MainActivity` deep-linked to the bound
  milestone's detail screen.

Update cadence has three layers:

- A **WorkManager** one-off, immediately after every milestone or binding change,
  so placed widgets re-render promptly. It's unconstrained (lands within ~a
  second) and durable across the app being backgrounded or killed right after the
  edit; it enqueues nothing when no widget is placed.
- A **WorkManager** periodic job (`WidgetRefreshWorker`) that re-renders every
  placed widget about **once an hour**, skipped while the battery is low. It's
  scheduled while any widget is placed, re-armed on app start, and cancelled once
  the last widget is removed. An hour is the deliberate battery-vs-freshness
  trade: *days* is the headline unit and only changes once a day, so the wide
  widget's hours/minutes are a nice-to-have rather than worth more frequent
  wake-ups. This is the **primary** periodic refresh.
- The platform's `updatePeriodMillis` (**6 h** on both widgets) as a coarse
  **backstop**. Unlike WorkManager, it still fires while the app is dormant
  (Doze / aggressive OEM battery managers), so the *day count* can't sit on the
  wrong day across a midnight while the app goes unopened. At 6 h it's far sparser
  than the hourly job — it no longer competes the way the old 30-min cadence did.
  (Caveat: a hard **force-stop** suspends every refresh path until the app is
  next opened, so a force-stopped-and-never-reopened widget can still go stale.)

In the foreground the app itself ticks once per minute, aligned to the minute
boundary, so the in-app detail screen stays live to the minute.

---

## Build & run

### Android Studio

1. Open the project root in Android Studio Otter 3 Feature Drop (2025.2.3) or newer
   (required by AGP 9.2 / the Kotlin 2.2 Compose compiler plugin).
2. Allow Gradle sync.
3. Run the `app` configuration on an emulator or device.

### Command line (Windows / PowerShell)

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

On macOS / Linux use the wrapper script instead, e.g. `./gradlew :app:assembleDebug`.

### Tests (Windows / PowerShell)

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

On macOS / Linux: `./gradlew :app:testDebugUnitTest`.

Notable test files:

- `ElapsedTimeTest` — date math, future-clamp, DST transitions.
- `EnglishDateFormatTest` — ordinal-suffix edge cases (11/12/13, 21st, 31st).
- `MilestoneJsonTest` — round-trip and defensive decoding of stored JSON.
- `MilestonesRepositoryBindingsTest` — widget binding serialisation,
  including the legacy bare-string format from before the transparent flag
  was added.

The `androidTest` source set also holds on-device tests (Compose UI, Glance
widgets, the DataStore repository, and an end-to-end navigation flow), run with
`connectedDebugAndroidTest` on a device or emulator.

On every push and pull request, CI runs three jobs — see
[`.github/workflows/ci.yml`](.github/workflows/ci.yml):

- **debug build + tests** — `assembleDebug` + `testDebugUnitTest`.
- **release build + lint** — `assembleRelease` + `lintRelease`.
- **instrumentation tests** — `connectedDebugAndroidTest` on an emulator
  (API 30).

---

## Placing a widget

1. Long-press an empty spot on your launcher.
2. Choose **Widgets** and find **Pulsar**.
3. Drag the widget onto the home screen.
4. A picker opens — choose which milestone the widget should track, and
   optionally enable the transparent background.
5. Tap the milestone to confirm.

The same flow works for both widget sizes. Each placement can track a
different milestone.

---

## Privacy

Pulsar stores all data locally in the app's private storage. It does
not request any runtime permissions, sends no personal data over the
network, and includes no analytics or crash reporting. The stored milestones are
included in Android Auto Backup so they survive an uninstall/reinstall
on the same Google account — you can turn that off under
*Settings → Backup & privacy*.

The full, hosting-ready privacy policy is at
[`docs/privacy-policy.md`](docs/privacy-policy.md); it doubles as the
Play-required privacy policy URL.

---

## Releasing

Releases are cut by pushing a `vMAJOR.MINOR.PATCH` tag, which triggers
[`.github/workflows/release.yml`](.github/workflows/release.yml) to build a
signed App Bundle and publish a GitHub Release. The full process — versioning,
signing secrets, and Play Console upload — is documented in
[`RELEASING.md`](RELEASING.md). User-facing history is in
[`CHANGELOG.md`](CHANGELOG.md); the Play store listing lives under
[`fastlane/metadata/android/`](fastlane/metadata/android).
