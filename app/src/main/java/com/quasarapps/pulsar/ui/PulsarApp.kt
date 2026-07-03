package com.quasarapps.pulsar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.quasarapps.pulsar.R
import com.quasarapps.pulsar.data.ThemeMode
import com.quasarapps.pulsar.ui.detail.DetailScreen
import com.quasarapps.pulsar.ui.edit.EditMilestoneScreen
import com.quasarapps.pulsar.ui.home.HomeScreen
import com.quasarapps.pulsar.ui.settings.SettingsScreen
import com.quasarapps.pulsar.ui.settings.SettingsViewModel
import com.quasarapps.pulsar.ui.splash.SplashScreen
import com.quasarapps.pulsar.ui.theme.PulsarTheme
import java.time.LocalDateTime

private object Routes {
    const val HOME = "home"
    const val ADD = "add"
    const val EDIT = "edit/{id}"
    const val DETAIL = "detail/{id}"
    const val SETTINGS = "settings"

    fun edit(id: String) = "edit/$id"
    fun detail(id: String) = "detail/$id"
}

/**
 * A milestone-detail deep link from a widget tap. [token] is a per-delivery nonce so repeated taps of
 * the same milestone are distinct values (each re-navigates), while recomposition stays a no-op.
 */
data class DeepLinkTarget(val milestoneId: String, val token: Long)

@Composable
fun PulsarApp(deepLink: DeepLinkTarget? = null) {
    // Read above PulsarTheme so the stored theme can drive it (follow-system until DataStore loads).
    val settingsVm: SettingsViewModel = viewModel()
    val settings by settingsVm.settings.collectAsState()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    PulsarTheme(darkTheme = darkTheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            val vm: MilestonesViewModel = viewModel()
            val milestones by vm.milestones.collectAsState()

            // Splash overlay state (declared before the content so it can gate the content's a11y).
            // Skipped on a widget deep-link so the tapped milestone shows immediately; rememberSaveable
            // stops a rotation mid-splash from replaying it.
            var splashDone by rememberSaveable { mutableStateOf(deepLink != null) }

            // Deep-link from a widget tap: jump straight to that milestone's detail. Keyed on [deepLink]
            // (new per delivery) so it fires for each tap — even of the same milestone — but not on
            // recomposition/rotation (the host only delivers on a fresh start or onNewIntent).
            // launchSingleTop avoids stacking a duplicate detail on a double tap.
            LaunchedEffect(deepLink) {
                if (deepLink != null) {
                    // Dismiss the splash so it can't linger over the deep-linked detail (no-op on a
                    // cold-start deep link, where splashDone is already true).
                    splashDone = true
                    navController.navigate(Routes.detail(deepLink.milestoneId)) {
                        launchSingleTop = true
                    }
                }
            }

            // Undo-delete: surface the view model's most recent deletion as an app-level snackbar
            // (delete pops Detail→Home, so the host can't live on one screen). Undo restores the
            // milestone + its widget bindings; dismiss/timeout drops it.
            val snackbarHostState = remember { SnackbarHostState() }
            val pendingUndo by vm.pendingUndo.collectAsState()
            // Resolve the snackbar strings in composition via stringResource rather than
            // context.getString(...) inside the effect: the latter trips Compose's
            // LocalContextGetResourceValueCall lint check (and doesn't react to config changes). Keyed on
            // pendingUndo, so the message re-resolves for each deletion.
            val undoActionLabel = stringResource(R.string.action_undo)
            val undoMessage = pendingUndo?.let {
                stringResource(R.string.detail_delete_snackbar, it.milestone.title)
            }
            LaunchedEffect(pendingUndo) {
                val message = undoMessage ?: return@LaunchedEffect
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = undoActionLabel,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )
                when (result) {
                    SnackbarResult.ActionPerformed -> vm.undoDelete()
                    SnackbarResult.Dismissed -> vm.clearPendingUndo()
                }
            }

            // App content (composes underneath the splash). While the splash is up, clear the
            // semantics behind it so a screen reader stays within the modal splash.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (splashDone) Modifier else Modifier.clearAndSetSemantics {}),
            ) {
                NavHost(navController = navController, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        // Apply the user's sort (remembered so it only re-sorts when list/order change).
                        val sorted = remember(milestones, settings.sortOrder) {
                            settings.sortOrder.sort(milestones)
                        }
                        HomeScreen(
                            milestones = sorted,
                            onAdd = { navController.navigate(Routes.ADD) },
                            onOpen = { id -> navController.navigate(Routes.detail(id)) },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            sortOrder = settings.sortOrder,
                            onSetSortOrder = settingsVm::setSortOrder,
                        )
                    }

                    composable(Routes.ADD) {
                        EditMilestoneScreen(
                            existing = null,
                            onSave = { title, date, time, accent ->
                                vm.addMilestone(title, date, time, accent)
                                navController.popBackStack()
                            },
                            onCancel = { navController.popBackStack() },
                        )
                    }

                    composable(
                        route = Routes.EDIT,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("id")
                        val existing = milestones.firstOrNull { it.id == id }
                        EditMilestoneScreen(
                            existing = existing,
                            onSave = { title, date, time, accent ->
                                // Only update from the edit route — if `existing` is null the milestone
                                // is gone, so just dismiss rather than spawning a new one.
                                if (existing != null) {
                                    vm.updateMilestone(
                                        existing.copy(title = title, date = date, time = time, accent = accent),
                                    )
                                }
                                navController.popBackStack()
                            },
                            onCancel = { navController.popBackStack() },
                        )
                    }

                    composable(
                        route = Routes.DETAIL,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("id")
                        val milestone = milestones.firstOrNull { it.id == id }
                        DetailScreen(
                            milestone = milestone,
                            showUnits = settings.showUnits,
                            onBack = { navController.popBackStack() },
                            onEdit = { if (id != null) navController.navigate(Routes.edit(id)) },
                            onReset = {
                                if (milestone != null) {
                                    // Capture a single instant so the date and time can't straddle midnight.
                                    val now = LocalDateTime.now()
                                    vm.updateMilestone(
                                        milestone.copy(date = now.toLocalDate(), time = now.toLocalTime().withNano(0)),
                                    )
                                }
                            },
                            onDelete = {
                                if (id != null) vm.deleteMilestone(id)
                                navController.popBackStack()
                            },
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            settings = settings,
                            onSetThemeMode = settingsVm::setThemeMode,
                            onToggleUnits = settingsVm::setShowUnits,
                            onSetBackup = settingsVm::setBackupEnabled,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !splashDone,
                enter = EnterTransition.None,
                exit = fadeOut(animationSpec = tween(250)),
                modifier = Modifier.fillMaxSize(),
            ) {
                SplashScreen(onFinished = { splashDone = true })
            }

            // App-level so it survives the Detail→Home pop-back after a delete.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}
