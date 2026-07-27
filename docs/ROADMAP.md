# Pulsar — Improvement Roadmap

A sequenced plan for the improvements surfaced by the comprehensive code review. Items are
grouped into phases that each map to one or two focused PRs off `develop`. Phases are ordered
by value and risk: correctness first, then polish, then internal health, then features.

**Effort:** S = a few hours · M = ~1 day · L = multi-day
**Risk:** 🟢 low · 🟡 medium · 🔴 high

---

## Phase 0 — Correctness & quick wins ✅ (in progress)

Ship-blocking fixes for the next patch release. All low-risk, all with test coverage.

| # | Item | Effort | Risk | Status |
|---|------|--------|------|--------|
| 1 | Blank-title default → `R.string.milestone_default_title` (stop persisting the hardcoded English "Milestone") | S | 🟢 | this PR |
| 2 | Prune widget bindings when a widget is removed (`onDeleted` → `unbindWidget`) | S | 🟢 | this PR |
| 3 | `edit/{badId}` must not silently create a new milestone | S | 🟢 | this PR |
| 4 | Guard a future *time* on today's date (mirror the date picker's clamp) | S | 🟢 | this PR |
| 25 | Test: blank title resolves to the *localized* resource (en + de) | S | 🟢 | this PR |
| 26 | Test: removing a widget unbinds it | S | 🟢 | this PR |
| 37 | **Guard write-path JSON decode against silent data loss** — `MilestoneJson.decode` returns `emptyList()` on any parse failure; every `dataStore.edit` call reads and decodes the stored list before mutating it, so a corrupted store causes the next `upsert`/`delete`/`restore` to silently persist a single-item list and permanently destroy all other milestones. Fix: if decode returns empty for a non-blank stored value, abort the edit block rather than proceeding with an empty baseline. | S | 🟢 | ✅ this PR |
| 38 | **Tag `v1.0.1` patch release** — the R8 `WorkDatabase_Impl` launch crash (the Google Play "opens, then keeps crashing" rejection of versionCode 10000) and the `AppWidgetManager` NPE guard are already fixed in HEAD (`proguard-rules.pro`, `WidgetRefreshScheduler`). Cut the tag to ship the fix. | S | 🟢 | ✅ shipped — approved by Play |
| 39 | **Move `WidgetConfigActivity` binding into a `ViewModel`** — `bindWidget` + `refreshAll` run in a `rememberCoroutineScope()` (tied to the composition). A device rotation between the user's tap and the DataStore write cancels the scope, leaving the widget permanently unbound. Move the binding call to `viewModelScope` (survives rotation) or `lifecycleScope` (tied to the Activity). | S | 🟢 | ✅ this PR (`viewModelScope`) |
| 45 | Test: write-path decode failure does not overwrite existing milestones | S | 🟢 | ✅ this PR |
| 46 | Test: `WidgetConfigActivity` binding survives a configuration change | S | 🟢 | ✅ this PR |

**Acceptance:** non-English blank titles persist the translated default; the widget-bindings map
stays bounded across add/remove cycles; editing a deleted milestone dismisses instead of creating;
a future time on today no longer silently reads "0"; a corrupt `milestones_json` value cannot wipe
the milestone list on the next write; binding a widget survives rotation; unit + instrumented suites green;
`v1.0.1` tagged and published.

---

## Phase 1 — Widget refresh consolidation & deep-link robustness

One coherent, battery-aware refresh strategy; make the widget→app deep link survive an already-running task.

| # | Item | Effort | Risk |
|---|------|--------|------|
| 6 | Reconcile `updatePeriodMillis` (alarm) vs. WorkManager periodic — pick one | M | 🟡 |
| 7 | Gate `refreshNow` on `hasPlacedWidgets` (don't enqueue work for users with no widgets) | S | 🟢 |
| 8 | Remove the double redraw (in-process `refreshAll` + WorkManager backstop) | S | 🟡 |
| 9 | Call `ensureScheduled` on app start as a safety net | S | 🟢 |
| 5 | `MainActivity` deep-link robustness: `singleTop` + `onNewIntent` rewiring (moved from Phase 0 — needs intent→recompose plumbing, latent-only today) | M | 🟡 |
| 40 | **Widget binding GC pass** — if the app is force-stopped between a widget removal and the WorkManager unbind job running, the binding entry persists forever. On `MilestoneGlanceWidgetReceiver.onUpdate` (and app start), cross-reference stored binding IDs against `AppWidgetManager.getAppWidgetIds()` and prune any entries whose widget ID is no longer registered. | S | 🟢 |
| 41 | **Integration-test the dual refresh paths under battery/Doze constraints** — the three-layer refresh strategy (immediate one-off → hourly WorkManager → 6 h `updatePeriodMillis` backstop) is well-designed but the interaction between layers is untested. Add tests covering: (a) periodic work cancellation when the last widget is removed, (b) re-arming on app start after a force-stop wipes WorkManager's database, and (c) no duplicate work entries when `ensureScheduled` is called repeatedly. | M | 🟡 |

**Acceptance:** a single documented source of refresh truth; no WorkManager enqueue with zero widgets;
widget still updates within seconds of an edit and rolls over daily; re-tapping a widget while the app
runs deep-links correctly without losing in-app state; stale binding entries are pruned on the next
app start or widget update; no duplicate periodic work entries under rapid scheduling calls.

---

## Phase 2 — Localization & accessibility polish

User-facing quality across all 10 locales and assistive tech.

| # | Item | Effort | Risk |
|---|------|--------|------|
| 10 | Lint rule / audit for hardcoded user-facing strings | S | 🟢 |
| 11 | Localize the `9999+` widget cap format | S | 🟢 |
| 12 | Review abbreviated unit labels (DAYS/HRS/MIN) per locale | M | 🟢 |
| 13 | `Role.Button` + merged semantics on `MilestoneCard` | S | 🟢 |
| 14 | Card content description summarizing days + title | S | 🟢 |
| 15 | Verify the detail hero under large font scale | S | 🟡 |
| 16 | Verify WCAG AA contrast on the Solar accent + scrim | S | 🟢 |
| 27 | Snapshot tests for accent gradients & the "new beginning" state | M | 🟢 |
| 42 | **Reactive `rememberReduceMotion`** — the current `remember { Settings.Global.getFloat(…) }` (no key) reads `ANIMATOR_DURATION_SCALE` once at composition time and never re-reads it. The README advertises "respects the system reduce-motion setting" but this only holds at cold start; toggling it while the app is open has no effect until a restart. Replace with a `ContentObserver`-backed `produceState` that re-queries whenever the system setting changes. | S | 🟢 |

**Acceptance:** lint fails on new hardcoded strings; TalkBack announces each card as one labeled button;
contrast verified with a tool; snapshot baselines committed; toggling system reduce-motion while the
app is open immediately stops/starts the `CountUpNumber` animation.

---

## Phase 3 — Performance & internal refactors

Reduce recomposition churn and centralize wiring. Internal-only, no behavior change.

| # | Item | Effort | Risk |
|---|------|--------|------|
| 17 | Stable per-item keys in the home two-column layout | M | 🟡 |
| 18 | Verify `CountUpNumber` has no jank at large counts | S | 🟢 |
| 19 | `remember` the per-row day count in the widget config list | S | 🟢 |
| 20 | Centralize repository creation (singleton / service locator) | M | 🟡 |
| 21 | `ViewModelProvider.Factory` for the VM test seams | M | 🟢 |
| 22 | Extract the shared `isNew` ("0-day, not future") helper | S | 🟢 |
| 23 | Consider type-safe Compose navigation | M | 🟡 |
| 24 | Document/justify the two-DataStore split | S | 🟢 |
| 28 | Unit-test the `WidgetUi` font-size / cap pure functions | S | 🟢 |
| 34 | Migrate to `kotlinx-serialization` for milestone/binding JSON | M | 🟡 |
| 35 | In-memory `StateFlow` caching in `MilestonesRepository` | S | 🟢 |
| 36 | Review DST `ElapsedTime` logic vs. "Calendar Days" — also audit `SortOrder.MOST_DAYS`, which sorts by stored `date`/`time` fields (a calendar sort) rather than computed elapsed seconds. Two milestones on the same calendar date but across a DST transition sort identically despite having different real elapsed durations. Decide whether to document the calendar-sort contract explicitly or switch to a true elapsed-time sort via `ElapsedTime.sincePickedDhm`. | S | 🟡 |
| 43 | **Use a unique WorkManager enqueue for widget unbind** — `WidgetRefreshScheduler.unbindWidgets` calls `WorkManager.enqueue` (non-unique). Rapid widget removals queue multiple cleanup workers, each performing a separate DataStore write. Switch to `enqueueUniqueWork` with `ExistingWorkPolicy.APPEND_OR_REPLACE` and merge the `unbindIds` arrays, so concurrent removals batch into one write. | S | 🟢 |

**Acceptance:** no recomposition regressions; repositories created in one place; nav refactor (if done)
keeps all instrumented nav tests green. Migration to `kotlinx-serialization` preserves all existing
stored data (round-trip verified). `MOST_DAYS` sort contract documented or corrected. Unbind cleanup
batches into a single DataStore write per removal event.

---

## Phase 4 — Build, CI & tooling hardening

Keep the project healthy and current. Mostly infra; can run in parallel with Phases 2–3.

| # | Item | Effort | Risk | Status |
|---|------|--------|------|--------|
| 31 | Kover coverage threshold gate in CI | S | 🟢 | ✅ done |
| 32 | Renovate/Dependabot + version-catalog update automation | S | 🟢 | ✅ done (Dependabot) |
| 30 | Dependency bump pass (Compose BOM, navigation, etc.) | M | 🟡 | → via Dependabot PRs (#32) |
| 29 | Resolve the `MonochromeLauncherIcon` TODO (needs vector icon source) | M | 🟡 | ⛔ blocked on the vector icon asset |
| 33 | compileSdk/targetSdk migration | M | 🟡 | ✅ `compileSdk`→37 + cohort (#89); `targetSdk`→36 this PR |
| 44 | **Raise the Kover coverage floor incrementally** — the floor is set at 60% against a current JVM unit coverage of ~71%, leaving an 11-point gap where significant regressions go undetected before CI catches them. After each feature phase, bump the floor to within 5 points of the measured coverage, keeping it a meaningful safety net rather than a formality. | S | 🟢 | |

**Acceptance:** CI enforces a coverage floor; a bot opens dependency-update PRs; build green on bumped versions; Kover floor stays within 5 points of measured coverage after each phase.

### #33 — compileSdk / targetSdk migration plan

**Step 1 (this PR): `compileSdk = 37`, `targetSdk` stays 35.** The current AndroidX cohort forced
the compile target past the originally-planned 36: `core(-ktx) 1.19` and `lifecycle 2.11` require
`compileSdk 37`, `activity 1.13` / `navigation 2.9.8` require 36. AGP 9.3 (max `compileSdk 37`, min
Gradle 9.5 → wrapper 9.6.1) plus Kotlin 2.4 and Compose BOM 2026.06 move as one aligned cohort, so the
partial-bump binary skew (`NoSuchMethodError`) that failed #77/#87 doesn't recur. `compileSdk`-only means
newer APIs compile without opting into new runtime behavior.

**Step 2 (shipped): `targetSdk = 36`.** Required by Play for all app updates from Aug 30, 2026.
Opts into the Android 16 behavior changes; the audit found only three that touch this app, all
already satisfied: predictive back (no `onBackPressed`/`KEYCODE_BACK` anywhere — back runs through
Navigation Compose), edge-to-edge opt-out removal (already edge-to-edge; the dead XML system-bar
overrides were dropped), and sw≥600dp orientation/aspect-ratio freedom (nothing declared). CI runs
the instrumented suite on an API 36 GMD alongside the API 30 one, and the Robolectric suite runs at
SDK 36 (its default follows `targetSdk`). **Remaining before release:** a manual device pass on an
Android 16 device — back gestures (gesture + 3-button nav), widget → deep-link → back, insets on a
cutout device in landscape, and the widget refresh/rollover.

---

## Phase 5 — UX features & privacy (product backlog)

Net-new product value, re-planned into sequenced PRs (value/effort ordered). Drag-reorder and the
speculative refactors are explicitly parked (bottom).

| PR | Items | Theme | Status |
|----|-------|-------|--------|
| 1 | #34 (backup **toggle**, not exclude-only) + #35 (privacy note) + flaky-test fix | Privacy & backup control | ✅ done (#60) |
| 2 | #36 undo delete | Data-loss safety net | ✅ done (#61) |
| 3 | #39 ticker lifecycle pause | Quality & efficiency | ✅ done (#62) |
| 4 | #37 (sort modes) + #17 (grid / `animateItem`) | Home ordering | ✅ this PR |
| 5 | #27 accent/new-beginning snapshot tests (Roborazzi) | Visual regression net | optional |

¹ flaky-test hardening was folded into PR 1 (CI stability for the rest of the run).
² #15 (large-font detail-hero check) was originally bundled with #39 but is **deferred**: the real
fix is a non-trivial scrollable-vs-`weight`-centering rework, and whether it's even needed must be
confirmed on-device at a large font scale — not a blind change. Stays a tracked follow-up.

**#34 decision:** implemented as a **"Back up milestones" toggle** (default on) via a custom
`MilestoneBackupAgent` that gates Auto Backup on the setting — not a title-only exclusion, and not
app-level encryption (key-management would break restore; Android already encrypts Auto Backup with
the lockscreen key). #35 discloses this in-app.

### Parked — revisit on a real trigger, not pre-emptively
- **#37 drag-reorder** *(the L half of #37)* — needs a persisted order field + drag gesture; the sort modes shipped in PR 4 cover most of the need. *(The grid/`animateItem` half of #17 landed in PR 4.)*
- **#20 / #21** repo DI / `ViewModelProvider.Factory` — DataStore is already a process singleton; lateral churn.
- **#23** type-safe Compose navigation — low ROI for a 5-route graph.

---

## Sequencing at a glance

```
Phase 0  Correctness            → v1.0.1 patch release  (this PR + #38 tag)
Phase 1  Widget refresh + deep link
Phase 2  i18n + a11y polish
Phase 3  Perf + refactor
Phase 4  CI / tooling           (parallel, continuous)
Phase 5  UX + privacy           (product backlog)
```

## Release mapping

- **v1.0.1 patch:** Phase 0 — bug fixes only (includes R8 launch-crash fix, write-path JSON guard, WidgetConfigActivity rotation fix).
- **next minor:** Phases 1–3 — refresh, polish, internal health.
- **later minor/feature:** Phase 5 — undo, reorder, privacy.
- **Phase 4** lands continuously, not tied to a single release.
