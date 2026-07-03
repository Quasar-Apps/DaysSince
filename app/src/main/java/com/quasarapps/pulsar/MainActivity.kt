package com.quasarapps.pulsar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.quasarapps.pulsar.ui.DeepLinkTarget
import com.quasarapps.pulsar.ui.PulsarApp
import com.quasarapps.pulsar.widget.WidgetRefreshScheduler

class MainActivity : ComponentActivity() {

    // Most recent widget-tap deep link, as Compose state so the content re-navigates on each new one.
    // The activity is singleTop, so a tap while running arrives via onNewIntent, not a fresh onCreate.
    private var deepLink by mutableStateOf<DeepLinkTarget?>(null)

    // Per-delivery token so successive taps of the *same* milestone are distinct values and re-navigate.
    private var deliveryCount = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash during cold start; the richer wordmark/tagline moment is the in-app Compose splash.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Only consume the launch intent on a genuine fresh start — on a config-change recreation the
        // NavController restores the back stack itself, so re-delivering would snap back to the detail.
        if (savedInstanceState == null) deliverDeepLink(intent)

        // Re-arm the periodic widget refresh if any widget is placed (no-op otherwise), in case it was
        // lost. Best-effort: scheduling background work is not essential to opening the app, so a
        // failure here (e.g. an unavailable widget host or WorkManager init hiccup on some devices)
        // must never crash the launch. Mirrors the runCatching guard around refreshWidgets().
        runCatching { WidgetRefreshScheduler.ensureScheduledIfWidgetsPlaced(this) }

        setContent {
            PulsarApp(deepLink = deepLink)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() current for a later recreation, and route the tapped milestone to the composition.
        setIntent(intent)
        deliverDeepLink(intent)
    }

    private fun deliverDeepLink(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_MILESTONE_ID) ?: return
        deepLink = DeepLinkTarget(milestoneId = id, token = deliveryCount++)
    }

    companion object {
        const val EXTRA_MILESTONE_ID = "com.quasarapps.pulsar.MILESTONE_ID"
    }
}
