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

# Pin the name and constructor of EVERY worker — ours above, and the library ones. This exists for
# androidx.glance.session.SessionWorker: Glance performs every widget render inside that
# CoroutineWorker, WorkManager instantiates it reflectively by the class-name string written into its
# database at enqueue time, and Glance ships no keep rule of its own for it. Through work-runtime 2.9
# the library's consumer rule pinned every ListenableWorker subclass unconditionally
# (`-keep public class * extends androidx.work.ListenableWorker { public <init>(...); }`) — that rule
# is what kept widget rendering alive in v1.0.1 — but work-runtime 2.10+ relaxed it to `-keepnames`
# (allowshrinking), which under R8 full mode only protects a worker R8 already traced as reachable.
# If that reachability chain ever breaks, the failure mode is grim: no crash, no error box, tests
# green (debug skips R8) — WorkManager just logs "Could not instantiate" and every widget silently
# shows its initialLayout forever. Same defect class as the WorkDatabase_Impl rule above, pinned for
# the same reason.
-keep class * extends androidx.work.ListenableWorker {
    <init>(...);
}

# WorkManager (used for the periodic widget refresh) initializes at process startup via
# androidx.startup's InitializationProvider, which builds its Room-backed WorkDatabase. Room creates
# the generated WorkDatabase_Impl reflectively. room-runtime's own consumer rule keeps RoomDatabase
# subclasses by name (`-keep class * extends androidx.room.RoomDatabase`) but NOT their constructors,
# and under R8 full mode (the AGP 9 default) that leaves the generated implementation non-instantiable
# — so the app crashed at launch, before any UI, with "Failed to create an instance of ...WorkDatabase"
# from androidx.startup. This was the Google Play "opens, then keeps crashing" rejection of
# versionCode 10000; it reproduces only in the minified release build, never in debug (which skips R8),
# which is why the whole test suite stayed green. Pin the constructors of every RoomDatabase subclass
# so the generated implementation stays instantiable under R8.
-keep class * extends androidx.room.RoomDatabase {
    <init>(...);
}

# Keep the constructors of our ViewModel subclasses. Compose's viewModel() instantiates them
# reflectively — for our AndroidViewModels, SavedStateViewModelFactory finds no
# (Application, SavedStateHandle) constructor and falls through to AndroidViewModelFactory, which calls
# `modelClass.getConstructor(Application::class.java).newInstance(app)`, so that (Application) ctor must
# survive R8. lifecycle-viewmodel already ships a consumer rule that keeps it
# (`-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.AndroidViewModel { <init>(android.app.Application); }`),
# so this is defense-in-depth, not the fix for any known crash — insurance against a future lifecycle
# version dropping that rule (cf. the Hilt @HiltViewModel keep-rule regression, dagger#4739).
#
# -keepclassmembers (not -keep class): the classes are already reachable via their class literals at the
# viewModel<…>() call sites, so R8 keeps the types regardless — only the reflectively-invoked ctor needs
# pinning. allowobfuscation lets R8 still rename the class, which is safe because the factory resolves the
# ctor through the Class object, not by name. (Contrast the Room rule above, which is -keep class
# precisely because WorkDatabase_Impl is resolved reflectively BY NAME, so its name must survive.)
-keepclassmembers,allowobfuscation class com.quasarapps.pulsar.** extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Preserve source file names and line numbers in stack traces for easier debugging.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
