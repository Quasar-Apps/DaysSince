package com.quasarapps.pulsar.widget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quasarapps.pulsar.data.Milestone
import com.quasarapps.pulsar.data.MilestonesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds the widget-configuration state for [WidgetConfigActivity].
 *
 * The binding write lives here rather than in a `rememberCoroutineScope()` inside the activity's
 * composition: that scope is cancelled when the composition leaves, so a device rotation between the
 * user's tap and the DataStore write would cancel the write and leave the widget permanently
 * unbound (the activity had already been told to finish). [viewModelScope] is retained across
 * configuration changes, so the write always completes and the recreated activity picks the result
 * up from [bound].
 */
class WidgetConfigViewModel internal constructor(
    app: Application,
    private val repo: MilestonesRepository,
) : AndroidViewModel(app) {

    constructor(app: Application) : this(app, MilestonesRepository(app))

    val milestones: StateFlow<List<Milestone>> = repo.milestones
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _bound = MutableStateFlow(false)

    /**
     * Flips to true once the binding is durably written and the widgets have been refreshed — the
     * activity's cue to finish with RESULT_OK. Survives rotation with this view model, so the result
     * isn't lost if the activity is recreated mid-write.
     */
    val bound: StateFlow<Boolean> = _bound.asStateFlow()

    // Non-null only while a bind is actually in flight, so a double tap (or a re-tap after rotation)
    // can't enqueue a second write. Cleared in a finally below: a job left dangling after a failure
    // would swallow every subsequent tap.
    private var bindJob: Job? = null

    fun bind(appWidgetId: Int, milestoneId: String, transparent: Boolean) {
        if (_bound.value || bindJob != null) return
        bindJob = viewModelScope.launch {
            try {
                repo.bindWidget(appWidgetId, milestoneId, transparent)
                // Best-effort, and deliberately after the write: the binding is already durable, and
                // the widget re-renders on its next update regardless. A refresh failure
                // (AppWidgetManager is absent on some devices/profiles — same guard as
                // MainActivity.onCreate) must not stop the activity reporting success, or the user
                // would be left staring at an unconfigured widget.
                runCatching { MilestoneWidgets.refreshAll(getApplication()) }
                _bound.value = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (write: Throwable) {
                // The DataStore write failed (IO error, unreadable store). Leave `bound` false so the
                // activity stays put with its RESULT_CANCELED, rather than claiming a binding that was
                // never persisted — and let the exception stop here: an uncaught throw in
                // viewModelScope would take the whole app down. Tapping again retries.
            } finally {
                bindJob = null
            }
        }
    }
}
