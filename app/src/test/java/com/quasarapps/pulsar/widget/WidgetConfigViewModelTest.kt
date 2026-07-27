package com.quasarapps.pulsar.widget

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.quasarapps.pulsar.data.MilestonesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * Covers the binding write that [WidgetConfigActivity] delegates here.
 *
 * The bug this guards: the write used to run in the activity's `rememberCoroutineScope()`, which is
 * cancelled when the composition leaves. A rotation between the tap and the DataStore write cancelled
 * it, and because the old code also did `setResult(RESULT_OK)`/`finish()` inside that same cancelled
 * coroutine, the widget was left permanently unbound.
 *
 * Rotation survival comes from *where* the write runs: `viewModelScope` on a view model that the
 * framework retains across configuration changes. These tests pin the two halves of that contract —
 * the write completes and is durable, and its outcome is exposed as retained state ([bound]) that a
 * recreated activity re-reads, rather than a one-shot callback that a recreation would miss.
 *
 * All the coroutine machinery shares one [scheduler] so `runTest` can drive the view model's
 * fire-and-forget `bind()` to completion with [advanceUntilIdle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WidgetConfigViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val dataStoreScope = CoroutineScope(dispatcher)

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: MilestonesRepository
    private lateinit var vm: WidgetConfigViewModel

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // viewModelScope is hard-wired to Dispatchers.Main.
        Dispatchers.setMain(dispatcher)
        val file = File.createTempFile("pulsar_test_", ".preferences_pb").also { it.delete() }
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { file }
        repo = MilestonesRepository(dataStore)
        vm = WidgetConfigViewModel(app, repo)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun bind_persistsTheBinding() = runTest(scheduler) {
        vm.bind(appWidgetId = 42, milestoneId = "m1", transparent = true)
        advanceUntilIdle()

        val binding = repo.bindingForWidget(42)
        assertEquals("m1", binding?.milestoneId)
        assertTrue(binding?.transparent == true)
    }

    @Test
    fun bind_exposesCompletionAsRetainedState() = runTest(scheduler) {
        assertFalse("nothing bound yet", vm.bound.value)

        vm.bind(appWidgetId = 42, milestoneId = "m1", transparent = false)
        advanceUntilIdle()

        // Still true on every later read: a recreated activity re-reads this and finishes with
        // RESULT_OK, instead of the result being lost with the destroyed composition.
        assertTrue(vm.bound.value)
        assertTrue(vm.bound.value)
    }

    @Test
    fun bind_isIgnoredOnceOneHasBeenAccepted() = runTest(scheduler) {
        vm.bind(appWidgetId = 42, milestoneId = "first", transparent = false)
        // A double tap (or a re-tap after a recreation) must not overwrite the first choice —
        // whether the second lands while the first is still in flight or after it has completed.
        vm.bind(appWidgetId = 42, milestoneId = "second", transparent = true)
        advanceUntilIdle()

        val binding = repo.bindingForWidget(42)
        assertEquals("first", binding?.milestoneId)
        assertFalse(binding?.transparent == true)
    }

    @Test
    fun bind_leavesOtherWidgetsBindingsAlone() = runTest(scheduler) {
        repo.bindWidget(appWidgetId = 1, milestoneId = "other", transparent = false)

        vm.bind(appWidgetId = 2, milestoneId = "mine", transparent = false)
        advanceUntilIdle()

        assertEquals("other", repo.bindingForWidget(1)?.milestoneId)
        assertEquals("mine", repo.bindingForWidget(2)?.milestoneId)
    }

    @Test
    fun bind_whenTheWriteFails_staysUnboundAndCanBeRetried() = runTest(scheduler) {
        val flaky = FlakyDataStore(failuresRemaining = 1)
        val flakyRepo = MilestonesRepository(flaky)
        val flakyVm = WidgetConfigViewModel(app, flakyRepo)

        flakyVm.bind(appWidgetId = 7, milestoneId = "m1", transparent = false)
        advanceUntilIdle()

        // A write that threw must not report success — the activity keeps its RESULT_CANCELED rather
        // than claiming a binding that was never persisted.
        assertFalse("a failed write must not report success", flakyVm.bound.value)

        // ...and the failed attempt must not wedge the view model: the in-flight guard is cleared, so
        // tapping again retries instead of being silently swallowed forever.
        flakyVm.bind(appWidgetId = 7, milestoneId = "m1", transparent = false)
        advanceUntilIdle()

        assertTrue("a retry after a failure must be able to succeed", flakyVm.bound.value)
        assertEquals("m1", flakyRepo.bindingForWidget(7)?.milestoneId)
    }

    /** A DataStore that throws on its first [failuresRemaining] writes, then behaves normally. */
    private class FlakyDataStore(private var failuresRemaining: Int) : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> get() = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            if (failuresRemaining > 0) {
                failuresRemaining--
                throw IOException("simulated DataStore write failure")
            }
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
