# Keep the Glance widget receivers, their GlanceAppWidget subclasses, and the
# widget configuration activity — all referenced by name from AndroidManifest.xml
# and must survive R8 shrinking. A package wildcard is the most robust form
# because it survives future renames inside the widget layer.
-keep class com.quasarapps.pulsar.widget.** { *; }

# Keep MainActivity — it is the launcher activity declared in the manifest.
-keep class com.quasarapps.pulsar.MainActivity { *; }

# The backup agent is declared via android:backupAgent and driven entirely by the platform backup
# framework — it's instantiated reflectively AND its onFullBackup/onRestore overrides are invoked by
# the OS, never from app code. Keep the whole class (members included) so R8 can't strip those
# overrides and silently fall back to the default (ungated) backup in release builds.
-keep class com.quasarapps.pulsar.backup.MilestoneBackupAgent { *; }

# WorkManager's default WorkerFactory instantiates workers reflectively by class name, so the
# worker class and its (Context, WorkerParameters) constructor must survive R8. It also falls under
# the broad widget.** rule above, but declare it explicitly so narrowing that rule can't silently
# break the periodic widget refresh in release builds.
-keep class com.quasarapps.pulsar.widget.WidgetRefreshWorker { <init>(...); }

# Keep the constructors of our AndroidViewModel subclasses. Compose's viewModel() instantiates them
# reflectively: SavedStateViewModelFactory finds no (Application, SavedStateHandle) constructor and
# falls through to AndroidViewModelFactory, which calls
# `modelClass.getConstructor(Application::class.java).newInstance(app)` — so the (Application)
# constructor must survive R8. lifecycle-viewmodel already ships a consumer rule that keeps it
# (`-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel { <init>(android.app.Application); }`),
# so under R8 full mode (the AGP 9 default) it is currently retained without this rule. This rule is
# therefore defense-in-depth, not the fix for any known crash: it pins the constructors explicitly so a
# future lifecycle version dropping/narrowing that consumer rule can't silently break reflective
# ViewModel creation in release (cf. the Hilt @HiltViewModel keep-rule regression, dagger#4739).
-keep class com.quasarapps.pulsar.** extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Preserve source file names and line numbers in stack traces for easier debugging.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
