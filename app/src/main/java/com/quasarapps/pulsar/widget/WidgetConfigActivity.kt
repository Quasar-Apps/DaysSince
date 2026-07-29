@file:OptIn(ExperimentalMaterial3Api::class)

package com.quasarapps.pulsar.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quasarapps.pulsar.ElapsedTime
import com.quasarapps.pulsar.MainActivity
import com.quasarapps.pulsar.R
import com.quasarapps.pulsar.data.Milestone
import com.quasarapps.pulsar.ui.theme.PulsarTheme
import com.quasarapps.pulsar.ui.theme.accentBrush
import com.quasarapps.pulsar.util.LocalizedDateFormat

/**
 * Launched when a widget is placed (android:configure). Lets the user pick the milestone this widget
 * tracks (and whether to render transparently), binds it, and finishes with RESULT_OK.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(RESULT_CANCELED, resultIntent(appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        enableEdgeToEdge()

        setContent {
            PulsarTheme {
                val vm: WidgetConfigViewModel = viewModel()
                val milestones by vm.milestones.collectAsState()
                val bound by vm.bound.collectAsState()

                // The bind runs in the view model's retained scope, so it survives a rotation mid-write;
                // this is where its completion is turned into the activity result. On a recreation the
                // fresh activity re-reads the already-true state and finishes correctly.
                LaunchedEffect(bound) {
                    if (bound) {
                        setResult(RESULT_OK, resultIntent(appWidgetId))
                        finish()
                    }
                }

                WidgetConfigScreen(
                    milestones = milestones,
                    onPick = { id, transparent -> vm.bind(appWidgetId, id, transparent) },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                    },
                )
            }
        }
    }

    private fun resultIntent(id: Int): Intent =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
}

@Composable
private fun WidgetConfigScreen(
    milestones: List<Milestone>,
    onPick: (id: String, transparent: Boolean) -> Unit,
    onOpenApp: () -> Unit,
) {
    var transparent by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_config_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TransparentToggle(checked = transparent, onToggle = { transparent = !transparent })
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            if (milestones.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.widget_config_empty_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onOpenApp) { Text(stringResource(R.string.widget_config_open_app)) }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(milestones, key = { it.id }) { milestone ->
                        MilestoneRow(
                            milestone = milestone,
                            onClick = { onPick(milestone.id, transparent) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransparentToggle(checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.widget_config_transparent_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.widget_config_transparent_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun MilestoneRow(milestone: Milestone, onClick: () -> Unit) {
    // Computed inline, not remember()-ed: it's wall-clock-dependent, so caching could let the count go
    // stale (e.g. across midnight) while the picker is open. sincePickedDhm is cheap.
    val days = ElapsedTime.sincePickedDhm(milestone.date, milestone.time).days
    val locale = LocalConfiguration.current.locales[0]
    val dateText = remember(milestone.date, locale) {
        LocalizedDateFormat.formatLongDate(milestone.date, locale)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accentBrush(milestone.accent)),
            )
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = milestone.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.widget_config_row_subtitle,
                        days.toInt(),
                        days,
                        dateText,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
