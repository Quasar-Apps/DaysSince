#!/usr/bin/env bash
#
# Verifies that constructors reachable only by reflection survived R8 in the release build.
#
# Why this exists: twice now, a shipped Play build has been broken by R8 stripping a constructor that
# nothing calls directly. Neither failure was visible to any test — debug builds skip R8 entirely, so
# the whole suite stayed green while the release APK was broken.
#
#   versionCode 10000 — R8 dropped androidx.work.impl.WorkDatabase_Impl's constructor. WorkManager
#     initializes at process start via androidx.startup, so the app crashed before any UI. Google Play
#     rejected the release for "Broken Functionality".
#   versionCode 10001 — R8 dropped androidx.work.OverwritingInputMerger's no-arg constructor.
#     WorkerWrapper instantiates the InputMerger before running ANY one-time work, so every one-time
#     job failed with "Could not create Input Merger". Glance renders every widget as one-time work,
#     so no widget ever rendered and users saw invisible widgets.
#
# Both were caused by the same thing: a library's consumer ProGuard rule that pins a class NAME but
# not its members, which R8 full mode is free to strip. Library rules change (work-runtime 2.10
# relaxed its worker rule; 2.11 fixed its InputMerger rule), so app-side keep rules and this check
# are what keep the guarantee stable across dependency bumps.
#
# R8 writes seeds.txt listing everything its keep rules matched. A bare "com.example.Foo" line means
# only the class name is pinned; "com.example.Foo: Foo()" means that constructor is pinned too. This
# script asserts the latter for each entry point below.
#
# Usage: scripts/verify-release-keeps.sh [path/to/seeds.txt]

set -euo pipefail

SEEDS="${1:-app/build/outputs/mapping/release/seeds.txt}"

if [[ ! -f "$SEEDS" ]]; then
    echo "error: $SEEDS not found — run :app:assembleRelease first (needs isMinifyEnabled)." >&2
    exit 1
fi

# "<class>: <constructor signature>" exactly as R8 writes it in seeds.txt, plus a note explaining
# what breaks if it goes missing. Add an entry whenever the app takes on a new reflective entry point.
REQUIRED=(
    "androidx.work.OverwritingInputMerger: OverwritingInputMerger()|WorkerWrapper builds this before every one-time job; missing = all one-time work fails, no widget renders (versionCode 10001)"
    "androidx.work.impl.WorkDatabase_Impl: WorkDatabase_Impl()|Room builds this when WorkManager auto-initializes at startup; missing = crash on launch (versionCode 10000)"
    "androidx.glance.session.SessionWorker: SessionWorker(android.content.Context,androidx.work.WorkerParameters)|Glance runs every widget render in this worker; missing = widgets never render"
    "com.quasarapps.pulsar.widget.WidgetRefreshWorker: WidgetRefreshWorker(android.content.Context,androidx.work.WorkerParameters)|the periodic widget refresh; missing = widgets go stale"
)

failed=0
for entry in "${REQUIRED[@]}"; do
    signature="${entry%%|*}"
    consequence="${entry#*|}"
    if grep -qxF "$signature" "$SEEDS"; then
        echo "  ok    $signature"
    else
        echo "  FAIL  $signature"
        echo "        not pinned by any keep rule — $consequence"
        failed=1
    fi
done

if [[ $failed -ne 0 ]]; then
    cat >&2 <<'EOF'

R8 is free to strip the constructors listed above, and nothing else in the build will notice:
these classes are instantiated reflectively, so there is no call site to keep them alive and no
test that can catch it (debug builds skip R8). Shipping this way has broken production twice.

Fix by adding an explicit rule to app/proguard-rules.pro pinning the constructor, e.g.

    -keep class * extends androidx.work.InputMerger {
        <init>(...);
    }

then re-run :app:assembleRelease and this script. Do not silence the check by deleting an entry
unless the app genuinely no longer reaches that class.
EOF
    exit 1
fi

echo "All reflective entry points survived R8."
