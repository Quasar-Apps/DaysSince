# Changelog

All notable changes to Pulsar are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

User-facing release notes for the Play Store live under
[`fastlane/metadata/android/<locale>/changelogs/`](fastlane/metadata/android);
this file is the fuller, developer-facing history.

## [Unreleased]

### Fixed
- **Widgets never rendered in the shipped Play builds (10000/10001) — placed widgets were
  completely invisible.** Root cause confirmed from field logcat on a Galaxy S25 Ultra:
  `WM-WorkerWrapper: Could not create Input Merger androidx.work.OverwritingInputMerger`.
  Before running any one-time work, WorkManager reflectively instantiates the request's
  `InputMerger`; work-runtime 2.9's consumer rule kept InputMerger subclasses by name only,
  and R8 full mode strips the constructor a kept class never calls directly — so **every
  one-time WorkManager job in the shipped release build failed**, and Glance executes every
  widget render as one-time work. No render was ever posted; the launcher showed the
  pre-render placeholder forever. (Same defect class as the v1.0.1 `WorkDatabase_Impl`
  launch crash: a library consumer rule that doesn't survive R8 full mode, invisible to
  debug builds and the whole test suite.) Three-part fix:
  - work-runtime 2.11.2 (already shipped in the platform-upgrade cohort) fixes the library
    rule itself — verified in the release build's R8 seeds.
  - App-side ProGuard rules now pin the name **and constructor** of every
    `ListenableWorker` and every `InputMerger` unconditionally, so a future library-rule
    relaxation (exactly what happened to the worker rule in work-runtime 2.10) can't
    silently regress either reflective path again.
  - The pre-render placeholder (`widget_loading.xml`) was an empty transparent FrameLayout —
    which is what made this failure state *invisible* rather than visibly broken. It is now
    a branded card with a spinner, so any future render-blocking state reads as "loading"
    instead of nothing.
- **A corrupt store could silently destroy every milestone on the next write.** Every
  write is a read-modify-write, and the decode used to flatten "nothing stored" and
  "stored but unreadable" to the same empty list. So a single unreadable
  `milestones_json` value made the next `upsert`/`restore` rebuild the list from an
  empty baseline and persist it — permanently deleting every milestone the corrupt
  value held. Write paths now use a strict decode that returns null on unreadable
  data and abort the write, leaving the bytes on disk intact and recoverable. The
  identical hazard in the widget-bindings store is fixed the same way (binding one
  widget could unbind every other placed widget). Read paths stay lenient, so a
  corrupt store still shows an empty UI rather than crashing.
- **Configuring a widget no longer fails if the device is rotated mid-tap.** The
  binding write ran in the config activity's `rememberCoroutineScope()`, which is
  cancelled when the composition leaves; a rotation between the tap and the DataStore
  write cancelled the write — and the `RESULT_OK`/`finish()` that followed it in the
  same coroutine — leaving the widget permanently unbound. The write now runs in a
  retained `viewModelScope`, and its completion is exposed as state the recreated
  activity re-reads. The post-write widget refresh is also best-effort now, so an
  `AppWidgetManager` hiccup can't strand a widget that was already bound. Conversely,
  configuration now only reports success when the binding was genuinely persisted:
  `bindWidget` returns whether it wrote, so a write the repository had to abandon
  (unreadable bindings store, per the fix above) leaves the widget unplaced instead of
  placing one that is permanently stuck on its setup prompt.

### Changed
- **`targetSdk` 35→36 — the Google Play target-API-level requirement (mandatory for app
  updates from Aug 30, 2026).** Opts into the Android 16 behavior changes on Android 16+
  devices. Audit of what actually applies here: predictive back becomes the default back
  path (the app has no `onBackPressed`/`KEYCODE_BACK` handling — back already runs through
  Navigation Compose's dispatcher, which supports it); the edge-to-edge opt-out is removed
  (the app never used it — both activities call `enableEdgeToEdge()` and lay out with
  `safeDrawing` insets); orientation/resizability/aspect-ratio restrictions are ignored on
  sw≥600dp displays (none declared). The transparent system-bar overrides in the XML theme
  were dropped as dead config: `enableEdgeToEdge()` overrides them at runtime on every API
  level, and under the edge-to-edge enforcement that comes with targeting Android 15+ the
  window attributes are deprecated and generally have no effect. Robolectric's default test
  SDK follows `targetSdk`, so the JVM suite now runs on the SDK 36 android-all jar, and CI
  runs the instrumented suite on an API 36 managed device alongside the API 30 one.
- **Platform upgrade — `compileSdk` 35→37, Kotlin 2.2→2.4, AGP 9.2→9.3, and the AndroidX
  UI cohort.** Moved the whole compileSdk-coupled cohort in one deliberate step: Kotlin
  2.4.10 (Compose compiler +2 generations), Compose BOM 2026.06, AGP 9.3.0 (which raises
  the Gradle wrapper floor to 9.6.1), and `core-ktx`/`activity`/`navigation`/`lifecycle`/
  `work` to their current releases. Their AAR metadata requires `compileSdk` 36–37, so it
  moves to 37 (AGP 9.3's maximum); `targetSdk` stayed 35 in that step, decoupling the compile
  target from opting into new runtime behavior — the opt-in landed separately as the
  `targetSdk` 36 entry above. Bumping the cohort together avoids the partial-bump
  binary skew (`NoSuchMethodError` in the instrumented tests) that failed the earlier
  routine-bump attempts (#77, #87).

## [1.0.1] - 2026-07-23

### Fixed
- **Crash on launch in the release build — the Google Play "Broken Functionality"
  rejection (versionCode 10000).** WorkManager (used for the periodic widget
  refresh) auto-initializes at process startup via `androidx.startup`, building its
  Room-backed `WorkDatabase`. Under R8 full mode (the AGP 9 default), Room's
  generated `WorkDatabase_Impl` was left non-instantiable — `room-runtime`'s
  consumer keep-rule preserves the database class name but not its constructor — so
  the app crashed before any UI with `Failed to create an instance of
  …WorkDatabase`. It reproduced only in the minified release build, never in debug
  (which skips R8), which is why every unit and instrumented test stayed green.
  Fixed with an R8 keep-rule that pins the constructors of Room database
  implementations.
- Guarded a latent NPE on the same cold-start path: `WidgetRefreshScheduler`
  dereferenced `AppWidgetManager.getInstance()`, which is `null` on devices/profiles
  without `FEATURE_APP_WIDGETS`, and the whole widget-scheduling step in
  `MainActivity.onCreate` is now best-effort so it can never crash the launch.

### Testing
- Added `MainActivityLaunchInstrumentedTest`, a cold-start smoke test that launches
  the real `MainActivity` (previously every UI test hosted `PulsarApp` in a stub
  activity, so the launcher activity's `onCreate` was never exercised).

### Build
- Added a defense-in-depth R8 keep-rule for the app's `ViewModel` subclass
  constructors — insurance against a future `lifecycle-viewmodel` version dropping
  the consumer rule that currently keeps them (cf. the Hilt `@HiltViewModel`
  keep-rule regression).

## [1.0.0] - 2026-06-08

First public release.

### Added
- **Multiple milestones.** Track the days, hours, and minutes since any number
  of moments — your last night out, the last time you went to the gym, an anniversary. Each milestone gets
  its own accent from the Pulsar palette (Magenta, Violet, Indigo, Nebula,
  Aurora, Solar, Ember, Deep).
- **Detail screen** with a full-bleed gradient hero, a count-up animation, and a
  days / hours / minutes / seconds breakdown.
- **Two home-screen widgets** built with Jetpack Glance:
  - *Days Since* — a compact 1×1 widget showing whole days.
  - *Days · Hours · Minutes* — a wide widget with the full breakdown.
- **Per-widget configuration** — when you place a widget you pick the milestone
  it tracks and can opt into a transparent background (just the number, floating
  on your wallpaper).
- **Battery-aware widget refresh** — an immediate update after every edit, an
  hourly WorkManager job while any widget is placed, and a coarse 6-hour platform
  backstop so the day count rolls over across midnight even while the app is
  dormant.
- **Sort modes** on the home grid — recently added, most days, or alphabetical —
  with an animated staggered grid.
- **Undo delete** via a snackbar, so a mistaken removal is recoverable.
- **Reset to now** from the detail screen, to restart a count from the current
  moment.
- **Light / dark / system theme** setting, plus a toggle for the live
  hours/minutes/seconds readout.
- **Backup toggle** (default on) — gate whether milestones are included in
  Android Auto Backup, backed by a custom `MilestoneBackupAgent`.
- **Localized into 10 languages**: English, Arabic, German, Spanish, French,
  Italian, Hebrew, Polish, Portuguese, and Russian, with a per-app language
  picker (`localeConfig`).
- **Accessibility** — TalkBack descriptions on cards and widgets, respect for the
  system reduce-motion setting, and tabular figures so digits don't jump.

### Privacy
- Private by default: no accounts, no runtime permissions, no analytics, and no
  crash reporting; no personal data ever leaves the device. All data is stored in
  app-private DataStore preferences. The app is fully usable offline — the only
  network activity is an optional Android downloadable-fonts fetch (which sends
  just a font name). See [`docs/privacy-policy.md`](docs/privacy-policy.md).

### Technical
- Minimum Android 8.0 (API 26); targets Android 15 (API 35).
- Release builds are minified and resource-shrunk (R8) with explicit keep-rules
  for the reflectively-loaded widget receivers, backup agent, and refresh worker.
- DST-correct elapsed-time math (`ElapsedTime`), unit-tested across UTC, non-UTC
  zones, and both DST transition directions.

[Unreleased]: https://github.com/QuasarApps/Pulsar/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/QuasarApps/Pulsar/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/QuasarApps/Pulsar/releases/tag/v1.0.0
