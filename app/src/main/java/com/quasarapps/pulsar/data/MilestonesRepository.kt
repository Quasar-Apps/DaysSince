package com.quasarapps.pulsar.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

// Milestone data + widget bindings; separate from SettingsRepository's "pulsar_settings" store. The
// `by preferencesDataStore` delegate is a per-process singleton, so constructing
// MilestonesRepository(context) ad hoc (view model, widgets, config activity, worker) is cheap — each
// is a thin wrapper over the same store.
private val Context.milestonesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pulsar_store")

/** Per-appWidgetId configuration: which milestone, and whether to render transparently. */
data class WidgetBinding(
    val milestoneId: String,
    val transparent: Boolean = false,
)

/** What a widget needs to render: the (possibly absent) bound milestone and its transparent flag. */
data class WidgetRenderData(
    val milestone: Milestone?,
    val transparent: Boolean,
)

/**
 * What [MilestonesRepository.delete] removed for one milestone (the milestone + its widget bindings),
 * so [MilestonesRepository.restore] can reverse the delete verbatim for an undo.
 */
data class RemovedMilestone(
    val milestone: Milestone,
    val bindings: Map<Int, WidgetBinding>,
)

/**
 * Single source of truth for milestones and widget bindings, backed by Preferences DataStore.
 * Milestones are a JSON array (see [MilestoneJson]); bindings a JSON object of appWidgetId ->
 * {id, transparent} (legacy bare-string ids decode to transparent=false).
 */
class MilestonesRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {

    constructor(context: Context) : this(context.applicationContext.milestonesDataStore)

    val milestones: Flow<List<Milestone>> = dataStore.data.map { prefs ->
        MilestoneJson.decode(prefs[KEY_MILESTONES]).sortedByDescending { it.createdAt }
    }

    suspend fun snapshot(): List<Milestone> = milestones.first()

    suspend fun upsert(milestone: Milestone) {
        dataStore.edit { prefs ->
            // Abort rather than rebuild from an empty baseline: if the stored value is present but
            // unreadable, writing here would replace every milestone it holds with just this one.
            // Leaving the corrupt value untouched keeps the data recoverable. See decodeOrNull.
            val current = (MilestoneJson.decodeOrNull(prefs[KEY_MILESTONES]) ?: return@edit)
                .toMutableList()
            val idx = current.indexOfFirst { it.id == milestone.id }
            if (idx >= 0) current[idx] = milestone else current.add(milestone)
            prefs[KEY_MILESTONES] = MilestoneJson.encode(current)
        }
    }

    /**
     * Removes the milestone [id] and any widget bindings pointing at it, returning a [RemovedMilestone]
     * for undo via [restore] (or null if [id] wasn't present).
     */
    suspend fun delete(id: String): RemovedMilestone? {
        var removed: RemovedMilestone? = null
        dataStore.edit { prefs ->
            // Both decodes are strict (see decodeOrNull / decodeBindingsOrNull): a corrupt value here
            // would otherwise make this write drop every milestone, or every binding, it holds.
            val current = MilestoneJson.decodeOrNull(prefs[KEY_MILESTONES]) ?: return@edit
            val milestone = current.firstOrNull { it.id == id } ?: return@edit
            val allBindings = decodeBindingsOrNull(prefs[KEY_BINDINGS]) ?: return@edit
            removed = RemovedMilestone(milestone, allBindings.filterValues { it.milestoneId == id })
            prefs[KEY_MILESTONES] = MilestoneJson.encode(current.filterNot { it.id == id })
            prefs[KEY_BINDINGS] = encodeBindings(allBindings.filterValues { it.milestoneId != id })
        }
        return removed
    }

    /**
     * Reverses a [delete] for undo: re-inserts the [removed] milestone (if absent) and re-applies its
     * widget bindings. It keeps its original createdAt, so it returns to its original list position.
     */
    suspend fun restore(removed: RemovedMilestone) {
        dataStore.edit { prefs ->
            // Strict decodes: an undo must never be the write that destroys the rest of the list.
            val current = (MilestoneJson.decodeOrNull(prefs[KEY_MILESTONES]) ?: return@edit)
                .toMutableList()
            val bindings = (decodeBindingsOrNull(prefs[KEY_BINDINGS]) ?: return@edit).toMutableMap()
            if (current.none { it.id == removed.milestone.id }) current.add(removed.milestone)
            prefs[KEY_MILESTONES] = MilestoneJson.encode(current)
            bindings.putAll(removed.bindings)
            prefs[KEY_BINDINGS] = encodeBindings(bindings)
        }
    }

    // ---- widget bindings (appWidgetId -> WidgetBinding) ----

    suspend fun bindWidget(appWidgetId: Int, milestoneId: String, transparent: Boolean = false) {
        dataStore.edit { prefs ->
            // Strict decode: binding one widget must not unbind every other placed widget.
            val bindings = (decodeBindingsOrNull(prefs[KEY_BINDINGS]) ?: return@edit).toMutableMap()
            bindings[appWidgetId] = WidgetBinding(milestoneId, transparent)
            prefs[KEY_BINDINGS] = encodeBindings(bindings)
        }
    }

    suspend fun unbindWidget(appWidgetId: Int) {
        dataStore.edit { prefs ->
            val bindings = (decodeBindingsOrNull(prefs[KEY_BINDINGS]) ?: return@edit).toMutableMap()
            bindings.remove(appWidgetId)
            prefs[KEY_BINDINGS] = encodeBindings(bindings)
        }
    }

    suspend fun bindingForWidget(appWidgetId: Int): WidgetBinding? {
        val prefs = dataStore.data.first()
        return decodeBindings(prefs[KEY_BINDINGS])[appWidgetId]
    }

    /**
     * Reactive render state for a placed widget: the bound milestone (or null) and its transparent
     * flag. Emits on every data change, so a collecting widget re-renders with fresh data (an edited
     * milestone shows immediately, not just the one captured when first composed).
     */
    fun widgetRenderDataFlow(appWidgetId: Int): Flow<WidgetRenderData> =
        dataStore.data.map { prefs ->
            val binding = decodeBindings(prefs[KEY_BINDINGS])[appWidgetId]
            val milestone = binding?.let { b ->
                MilestoneJson.decode(prefs[KEY_MILESTONES]).firstOrNull { it.id == b.milestoneId }
            }
            WidgetRenderData(milestone, binding?.transparent ?: false)
        }.distinctUntilChanged()

    companion object {
        private val KEY_MILESTONES = stringPreferencesKey("milestones_json")
        private val KEY_BINDINGS = stringPreferencesKey("widget_bindings_json")

        internal fun encodeBindings(map: Map<Int, WidgetBinding>): String {
            val o = JSONObject()
            map.forEach { (k, v) ->
                o.put(
                    k.toString(),
                    JSONObject().apply {
                        put("id", v.milestoneId)
                        put("transparent", v.transparent)
                    },
                )
            }
            return o.toString()
        }

        /**
         * Decodes the bindings JSON, accepting both the current `{id, transparent}` shape and the
         * legacy plain-string id (upgraded to a [WidgetBinding] with transparent=false).
         *
         * Lenient (unreadable data reads as no bindings) — for read paths only. Writes must use
         * [decodeBindingsOrNull], which distinguishes "none" from "unreadable".
         */
        internal fun decodeBindings(json: String?): Map<Int, WidgetBinding> =
            decodeBindingsOrNull(json) ?: emptyMap()

        /**
         * Strict counterpart to [decodeBindings] for write paths: null when [json] is non-blank but
         * unparseable, so a read-modify-write can abort instead of replacing every stored binding
         * with the single one it was adding. Same rationale as [MilestoneJson.decodeOrNull].
         */
        internal fun decodeBindingsOrNull(json: String?): Map<Int, WidgetBinding>? {
            if (json.isNullOrBlank()) return emptyMap()
            return runCatching {
                val o = JSONObject(json)
                buildMap {
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val widgetId = key.toIntOrNull() ?: continue
                        val value = o.opt(key)
                        val binding: WidgetBinding? = when (value) {
                            is String -> if (value.isNotBlank()) WidgetBinding(value, false) else null
                            is JSONObject -> {
                                val id = value.optString("id")
                                if (id.isNotBlank()) {
                                    WidgetBinding(id, value.optBoolean("transparent", false))
                                } else null
                            }
                            else -> null
                        }
                        if (binding != null) put(widgetId, binding)
                    }
                }
            }.getOrNull()
        }
    }
}
