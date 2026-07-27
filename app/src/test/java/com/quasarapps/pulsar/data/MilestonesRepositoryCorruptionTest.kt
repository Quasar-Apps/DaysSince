package com.quasarapps.pulsar.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Data-loss regression tests for the read-modify-write paths.
 *
 * Every write decodes the stored JSON to build its baseline. When decoding was lenient, a stored
 * value that was present but unreadable decoded to "empty", so the next write persisted a list
 * containing only the milestone being added — permanently destroying everything the corrupt value
 * held. Writes now abort instead, leaving the bytes on disk untouched and the data recoverable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MilestonesRepositoryCorruptionTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val dataStoreScope = CoroutineScope(dispatcher)

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: MilestonesRepository

    // Declared here rather than reached for on the repository: the real keys are private, and
    // hard-coding them keeps this test honest about the on-disk names.
    private val keyMilestones = stringPreferencesKey("milestones_json")
    private val keyBindings = stringPreferencesKey("widget_bindings_json")

    /** Non-blank but unparseable — the case the lenient decode used to swallow. */
    private val corruptJson = "{{ truncated write"

    @Before
    fun setUp() {
        val file = File.createTempFile("pulsar_test_", ".preferences_pb").also { it.delete() }
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { file }
        repo = MilestonesRepository(dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    private fun milestone(id: String) = Milestone(
        id = id,
        title = "T-$id",
        date = LocalDate.of(2026, 1, 1),
        time = LocalTime.of(9, 0),
        accent = 0,
        createdAt = 0L,
    )

    private suspend fun seed(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    private suspend fun stored(key: Preferences.Key<String>): String? = dataStore.data.first()[key]

    // ---- milestones ----

    @Test
    fun upsert_withCorruptMilestones_leavesStoredValueUntouched() = runTest(scheduler) {
        seed(keyMilestones, corruptJson)

        repo.upsert(milestone("new"))

        // The corrupt value must survive verbatim: overwriting it here is exactly the data loss.
        assertEquals(corruptJson, stored(keyMilestones))
    }

    @Test
    fun restore_withCorruptMilestones_leavesStoredValueUntouched() = runTest(scheduler) {
        seed(keyMilestones, corruptJson)

        repo.restore(RemovedMilestone(milestone("undone"), emptyMap()))

        assertEquals(corruptJson, stored(keyMilestones))
    }

    @Test
    fun delete_withCorruptMilestones_writesNothingAndReportsNothingRemoved() = runTest(scheduler) {
        seed(keyMilestones, corruptJson)

        val removed = repo.delete("anything")

        assertNull("nothing can be reported deleted out of unreadable data", removed)
        assertEquals(corruptJson, stored(keyMilestones))
    }

    // ---- widget bindings (same read-modify-write hazard) ----

    @Test
    fun bindWidget_withCorruptBindings_leavesStoredValueUntouched() = runTest(scheduler) {
        seed(keyBindings, corruptJson)

        repo.bindWidget(appWidgetId = 7, milestoneId = "m1", transparent = true)

        // Binding one widget must not unbind every other placed widget.
        assertEquals(corruptJson, stored(keyBindings))
    }

    @Test
    fun unbindWidget_withCorruptBindings_leavesStoredValueUntouched() = runTest(scheduler) {
        seed(keyBindings, corruptJson)

        repo.unbindWidget(appWidgetId = 7)

        assertEquals(corruptJson, stored(keyBindings))
    }

    @Test
    fun delete_withCorruptBindings_keepsBothValuesUntouched() = runTest(scheduler) {
        repo.upsert(milestone("a"))
        val goodMilestones = stored(keyMilestones)
        seed(keyBindings, corruptJson)

        val removed = repo.delete("a")

        // The whole edit aborts, so neither key is rewritten — no half-applied delete.
        assertNull(removed)
        assertEquals(goodMilestones, stored(keyMilestones))
        assertEquals(corruptJson, stored(keyBindings))
        assertEquals(listOf("a"), repo.snapshot().map { it.id })
    }

    // ---- the guard must not fire on legitimately-empty stores ----

    @Test
    fun writesStillWorkOnAnEmptyStore() = runTest(scheduler) {
        repo.upsert(milestone("a"))
        repo.upsert(milestone("b"))
        repo.bindWidget(appWidgetId = 1, milestoneId = "a")

        assertEquals(setOf("a", "b"), repo.snapshot().map { it.id }.toSet())
        assertEquals("a", repo.bindingForWidget(1)?.milestoneId)
    }

    @Test
    fun writesStillWorkOnABlankStoredValue() = runTest(scheduler) {
        // Blank is "nothing stored", not "unreadable" — it must not trip the abort.
        seed(keyMilestones, "")

        repo.upsert(milestone("a"))

        assertEquals(listOf("a"), repo.snapshot().map { it.id })
    }

    // ---- the decode contract the guards rest on ----

    @Test
    fun decodeOrNull_distinguishesAbsentFromUnreadable() {
        assertEquals(emptyList<Milestone>(), MilestoneJson.decodeOrNull(null))
        assertEquals(emptyList<Milestone>(), MilestoneJson.decodeOrNull(""))
        assertEquals(emptyList<Milestone>(), MilestoneJson.decodeOrNull("   "))
        assertEquals(emptyList<Milestone>(), MilestoneJson.decodeOrNull("[]"))
        assertNull(MilestoneJson.decodeOrNull(corruptJson))
        assertNull(MilestoneJson.decodeOrNull("not json"))

        // The lenient read-path decode still flattens both cases to an empty list.
        assertTrue(MilestoneJson.decode(corruptJson).isEmpty())

        // A well-formed array still round-trips (the strict path isn't over-eager).
        val encoded = MilestoneJson.encode(listOf(milestone("a")))
        assertNotNull(MilestoneJson.decodeOrNull(encoded))
        assertEquals(listOf("a"), MilestoneJson.decodeOrNull(encoded)?.map { it.id })
    }

    @Test
    fun decodeBindingsOrNull_distinguishesAbsentFromUnreadable() {
        assertEquals(emptyMap<Int, WidgetBinding>(), MilestonesRepository.decodeBindingsOrNull(null))
        assertEquals(emptyMap<Int, WidgetBinding>(), MilestonesRepository.decodeBindingsOrNull(""))
        assertEquals(emptyMap<Int, WidgetBinding>(), MilestonesRepository.decodeBindingsOrNull("{}"))
        assertNull(MilestonesRepository.decodeBindingsOrNull(corruptJson))
        assertNull(MilestonesRepository.decodeBindingsOrNull("[1,2,3]"))

        assertTrue(MilestonesRepository.decodeBindings(corruptJson).isEmpty())
    }
}
