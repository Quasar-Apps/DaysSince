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

**Acceptance:** non-English blank titles persist the translated default; the widget-bindings map
stays bounded across add/remove cycles; editing a deleted milestone dismisses instead of creating;
a future time on today no longer silently reads "0"; unit + instrumented suites green.

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

**Acceptance:** a single documented source of refresh truth; no WorkManager enqueue with zero widgets;
widget still updates within seconds of an edit and rolls over daily; re-tapping a widget while the app
runs deep-links correctly without losing in-app state.

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

**Acceptance:** lint fails on new hardcoded strings; TalkBack announces each card as one labeled button;
contrast verified with a tool; snapshot baselines committed.

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
| 36 | Review DST `ElapsedTime` logic vs. "Calendar Days" | S | 🟡 |

**Acceptance:** no recomposition regressions; repositories created in one place; nav refactor (if done)
keeps all instrumented nav tests green. Migration to `kotlinx-serialization` preserves all existing
stored data (round-trip verified).

---

## Phase 4 — Build, CI & tooling hardening

Keep the project healthy and current. Mostly infra; can run in parallel with Phases 2–3.

| # | Item | Effort | Risk | Status |
|---|------|--------|------|--------|
| 31 | Kover coverage threshold gate in CI | S | 🟢 | ✅ done |
| 32 | Renovate/Dependabot + version-catalog update automation | S | 🟢 | ✅ done (Dependabot) |
| 30 | Dependency bump pass (Compose BOM, navigation, etc.) | M | 🟡 | → via Dependabot PRs (#32) |
| 29 | Resolve the `MonochromeLauncherIcon` TODO (needs vector icon source) | M | 🟡 | ⛔ blocked on the vector icon asset |
| 33 | Plan the compileSdk/targetSdk 36 migration | M | 🟡 | ✅ plan below |

**Acceptance:** CI enforces a coverage floor; a bot opens dependency-update PRs; build green on bumped versions.

### #33 — compileSdk / targetSdk 36 migration plan

Currently `compileSdk = 35` / `targetSdk = 35` (Android 15). When Android 16 (SDK 36) tooling is stable, migrate as a dedicated PR:

1. **Tooling:** install the SDK 36 platform; confirm the AGP version in use supports `compileSdk = 36` (bump AGP first if needed — let Dependabot surface it).
2. **Bump `compileSdk = 36`** first, keeping `targetSdk = 35`. Build + run lint: `compileSdk`-only changes surface new deprecations / lint checks without opting into behavior changes. Fix any new errors.
3. **Bump `targetSdk = 36`** and review the Android 16 behavior changes that apply to this app: predictive-back, edge-to-edge enforcement (already edge-to-edge — verify insets), foreground-service / scheduling changes (we use WorkManager + an `updatePeriodMillis` alarm — verify the widget refresh still behaves), and any notification changes (none used today).
4. **Test:** full unit + instrumented suite on an API 36 managed device (add a `pixel*api36` GMD alongside the API 30 one, or bump it) and a manual device pass on the widget + deep-link + edit flows.
5. **Update** the `OldTargetApi` lint note in `app/lint.xml` and this roadmap once shipped.

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
Phase 0  Correctness            → next patch release   (this PR)
Phase 1  Widget refresh + deep link
Phase 2  i18n + a11y polish
Phase 3  Perf + refactor
Phase 4  CI / tooling           (parallel, continuous)
Phase 5  UX + privacy           (product backlog)
```

## Release mapping

- **patch:** Phase 0 — bug fixes only.
- **next minor:** Phases 1–3 — refresh, polish, internal health.
- **later minor/feature:** Phase 5 — undo, reorder, privacy.
- **Phase 4** lands continuously, not tied to a single release.
