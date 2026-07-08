package com.quasarapps.pulsar.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.quasarapps.pulsar.widget.glance.DaysHoursMinutesWidgetReceiver
import com.quasarapps.pulsar.widget.glance.DaysWidgetReceiver
import java.util.concurrent.TimeUnit

/**
 * Schedules the periodic [WidgetRefreshWorker] while at least one widget is placed.
 *
 * Favours battery over freshness: an hourly refresh skipped on low battery, rather than WorkManager's
 * 15-minute floor or per-minute alarms. That brings the DHM widget from ~6 h stale to ~1 h and catches
 * the once-a-day rollover that matters. This is the *primary* periodic refresh (armed from each
 * provider's `onUpdate`, re-armed on app start via [ensureScheduledIfWidgetsPlaced], persisted across
 * reboots). The widgets also keep a coarse 6 h `updatePeriodMillis` alarm as a backstop that, unlike
 * WorkManager, still fires while the app is dormant — keeping the day count off the wrong day.
 */
object WidgetRefreshScheduler {

    /** Refresh cadence — hourly (battery matters more than to-the-minute freshness). */
    const val REFRESH_INTERVAL_MINUTES = 60L

    private val widgetProviders = listOf(
        DaysWidgetReceiver::class.java,
        DaysHoursMinutesWidgetReceiver::class.java,
    )

    /**
     * Ensures the periodic refresh is running. Idempotent ([ExistingPeriodicWorkPolicy.KEEP]), so safe
     * to call from every `onUpdate`. Returns the enqueue [Operation] (tests await it for determinism).
     */
    fun ensureScheduled(context: Context): Operation {
        // Don't wake the device on low battery; a slightly stale count is an acceptable trade.
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
            REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES,
        ).setConstraints(constraints).build()
        return WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WidgetRefreshWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Re-arms the periodic refresh on app start, but only if a widget is placed (so a user with none
     * never schedules background work). Restores the hourly job promptly if it was ever lost — e.g.
     * the OS dropped it or an app-data clear wiped WorkManager's db.
     */
    fun ensureScheduledIfWidgetsPlaced(context: Context) {
        if (hasPlacedWidgets(context)) ensureScheduled(context)
    }

    /** Unique name for the one-off, data-changed refresh (kept separate from the periodic work). */
    const val IMMEDIATE_WORK_NAME = "milestone_widget_refresh_now"

    /**
     * Enqueues a one-off refresh after a milestone change so placed widgets re-render promptly. Routed
     * through WorkManager so it still runs if the app is torn down right after the edit; unconstrained,
     * so it lands within ~a second. [ExistingWorkPolicy.REPLACE] collapses rapid edits into one
     * refresh. Returns null when no widget is placed.
     */
    fun refreshNow(context: Context): Operation? {
        if (!hasPlacedWidgets(context)) return null
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
        return WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Removes the saved bindings for deleted [appWidgetIds] so the bindings map stays bounded. Runs
     * the suspend write inside [WidgetRefreshWorker] rather than the receiver's `onDeleted` (whose
     * broadcast thread / single `goAsync()` slot is already claimed). Enqueued non-uniquely so
     * concurrent deletions each carry their own ids.
     */
    fun unbindWidgets(context: Context, appWidgetIds: IntArray): Operation {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInputData(workDataOf(WidgetRefreshWorker.KEY_UNBIND_IDS to appWidgetIds))
            .build()
        return WorkManager.getInstance(context).enqueue(request)
    }

    /** Cancels the periodic refresh once no widgets of either type remain placed. */
    fun cancelIfNoWidgets(context: Context) {
        if (!hasPlacedWidgets(context)) {
            WorkManager.getInstance(context).cancelUniqueWork(WidgetRefreshWorker.UNIQUE_WORK_NAME)
        }
    }

    private fun hasPlacedWidgets(context: Context): Boolean {
        // AppWidgetManager.getInstance can return null on devices/profiles that don't declare
        // FEATURE_APP_WIDGETS (some emulators, TV, restricted profiles). This is called synchronously
        // from MainActivity.onCreate, so an unguarded null deref would crash the app at launch there;
        // treat "no widget host" as "no widgets placed".
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return widgetProviders.any { provider ->
            manager.getAppWidgetIds(ComponentName(context, provider)).isNotEmpty()
        }
    }
}
