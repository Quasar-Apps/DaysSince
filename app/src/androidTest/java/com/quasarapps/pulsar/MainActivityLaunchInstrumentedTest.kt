package com.quasarapps.pulsar

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start smoke test for the real launcher activity.
 *
 * Every other UI test hosts [com.quasarapps.pulsar.ui.PulsarApp] in a stub Compose activity, so nothing
 * else exercises [MainActivity.onCreate] — installSplashScreen, enableEdgeToEdge, the widget-refresh
 * scheduling, and setContent. That gap is exactly how a launcher-only launch crash could reach the Play
 * Store unnoticed. This test launches the genuine [MainActivity] end-to-end and asserts it reaches
 * RESUMED without throwing.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchInstrumentedTest {

    @Test
    fun launchesToResumedWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
