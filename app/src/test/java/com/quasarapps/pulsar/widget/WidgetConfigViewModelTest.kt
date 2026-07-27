package com.quasarapps.pulsar.widget

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.quasarapps.pulsar.data.MilestonesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WidgetConfigViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val dataStoreScope = CoroutineScope(dispatcher)

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: MilestonesRepository
    private lateinit var vm: WidgetConfigViewModel

    @Before
    fun setUp() {
        // viewModelScope is hard-wired to Dispatchers.Main.
        Dispatchers.setMain(dispatcher)
        val file = File.createTempFile("pulsar_test_", ".preferences_pb").also { it.delete() }
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { file }
        repo = MilestonesRepository(dataStore)
        vm = WidgetConfigViewModel(ApplicationProvider.getApplicationContext<Application>(), repo)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun bind_persistsTheBinding() = runTest {
        vm.bind(appWidgetId = 42, milestoneId = "m1", transparent = true)

        val binding = repo.bindingForWidget(42)
        assertEquals("m1", binding?.milestoneId)
        assertTrue(binding?.transparent == true)
    }

    @Test
    fun bind_exposesCompletionAsRetainedState() = runTest {
        assertFalse("nothing bound yet", vm.bound.value)

        vm.bind(appWidgetId = 42, milestoneId = "m1", transparent = false)

        // Still true on every later read: a recreated activity re-reads this and finishes with
        // RESULT_OK, instead of the result being lost with the destroyed composition.
        assertTrue(vm.bound.value)
        assertTrue(vm.bound.value)
    }

    @Test
    fun bind_isIgnoredWhileOneIsAlreadyInFlight() = runTest {
        vm.bind(appWidgetId = 42, milestoneId = "first", transparent = false)
        // A double tap (or a re-tap after a recreation) must not overwrite the first choice.
        vm.bind(appWidgetId = 42, milestoneId = "second", transparent = true)

        val binding = repo.bindingForWidget(42)
        assertEquals("first", binding?.milestoneId)
        assertFalse(binding?.transparent == true)
    }

    @Test
    fun bind_leavesOtherWidgetsBindingsAlone() = runTest {
        repo.bindWidget(appWidgetId = 1, milestoneId = "other", transparent = false)

        vm.bind(appWidgetId = 2, milestoneId = "mine", transparent = false)

        assertEquals("other", repo.bindingForWidget(1)?.milestoneId)
        assertEquals("mine", repo.bindingForWidget(2)?.milestoneId)
    }
}
