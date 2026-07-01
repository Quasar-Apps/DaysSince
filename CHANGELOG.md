# Changelog

All notable changes to Pulsar are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

User-facing release notes for the Play Store live under
[`fastlane/metadata/android/<locale>/changelogs/`](fastlane/metadata/android);
this file is the fuller, developer-facing history.

## [Unreleased]

### Fixed
- **Hardened the cold-start path against a launch crash.**
  `MainActivity.onCreate` re-arms the widget refresh via
  `WidgetRefreshScheduler.hasPlacedWidgets`, which dereferenced
  `AppWidgetManager.getInstance(context)` without a null check. That call returns
  `null` on devices/profiles without `FEATURE_APP_WIDGETS` (some emulators, TV,
  restricted profiles), so it could NPE at launch. It now treats a missing widget
  host as "no widgets placed", and the whole scheduling step is wrapped in a
  best-effort guard so no widget/WorkManager failure can crash the launch.

### Testing
- Added `MainActivityLaunchInstrumentedTest`, a cold-start smoke test that launches
  the real `MainActivity` to RESUMED. Previously every UI test hosted `PulsarApp`
  in a stub activity, so nothing exercised the launcher activity's `onCreate`
  (splash install, edge-to-edge, widget scheduling) — the gap through which a
  launch crash could reach the store.

### Build
- Added a defense-in-depth R8 keep-rule for the app's `ViewModel` subclass
  constructors. `lifecycle-viewmodel`'s own consumer rule already keeps them, so
  this is insurance against a future library version dropping that rule (cf. the
  Hilt `@HiltViewModel` keep-rule regression), not a fix for a current crash.

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

[Unreleased]: https://github.com/QuasarApps/Pulsar/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/QuasarApps/Pulsar/releases/tag/v1.0.0
