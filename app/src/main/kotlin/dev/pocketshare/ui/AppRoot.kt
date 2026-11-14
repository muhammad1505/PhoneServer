package dev.pocketshare.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pocketshare.R
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.ui.components.AppMessageDialog
import dev.pocketshare.ui.components.CreateShareDialog
import dev.pocketshare.ui.components.LocalUiMessageHost
import dev.pocketshare.ui.components.WhatsNewDialog
import dev.pocketshare.ui.screens.AboutScreen
import dev.pocketshare.ui.screens.DiagnosticsScreen
import dev.pocketshare.ui.screens.HomeScreen
import dev.pocketshare.ui.screens.SettingsScreen
import dev.pocketshare.ui.screens.SharesScreen
import dev.pocketshare.ui.viewmodel.SharesViewModel
import dev.pocketshare.ui.viewmodel.WhatsNewViewModel

private enum class Destination(
    val route: String,
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    SHARES("shares", R.string.nav_shares, Icons.Outlined.Folder, Icons.Filled.Folder),
    SETTINGS("settings", R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
    DIAGNOSTICS("diagnostics", R.string.nav_diagnostics, Icons.Outlined.BugReport, Icons.Filled.BugReport),
    ABOUT("about", R.string.nav_about, Icons.Outlined.Info, Icons.Filled.Info),
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val createViewModel: SharesViewModel = hiltViewModel()
    val creating by createViewModel.creating.collectAsStateWithLifecycle()
    val settings by createViewModel.settings.collectAsStateWithLifecycle()
    val whatsNewViewModel: WhatsNewViewModel = hiltViewModel()
    val whatsNew by whatsNewViewModel.release.collectAsStateWithLifecycle()

    var pendingKindName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUris by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var message by remember { mutableStateOf<UiMessage?>(null) }
    val showMessage: (UiMessage) -> Unit = { message = it }

    RequestNotificationPermission()

    LaunchedEffect(createViewModel) {
        createViewModel.messages.collect(showMessage)
    }

    val filesLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                pendingKindName = ShareKind.FILES.name
                pendingUris = ArrayList(uris.map(Uri::toString))
            }
        }
    val folderLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                pendingKindName = ShareKind.FOLDER.name
                pendingUris = arrayListOf(uri.toString())
            }
        }

    val onPickFiles: () -> Unit = { filesLauncher.launch(arrayOf("*/*")) }
    val onPickFolder: () -> Unit = { folderLauncher.launch(null) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Destination.HOME.route

    fun navigateTo(destination: Destination) {
        if (currentRoute == destination.route) return
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(LocalUiMessageHost provides showMessage) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        Destination.entries.forEach { destination ->
                            val selected = currentRoute == destination.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(destination) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                                        contentDescription = null,
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(destination.label),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    )
                                },
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Destination.HOME.route,
                modifier = Modifier.padding(innerPadding),
                enterTransition = {
                    fadeIn(tween(220)) +
                        slideInHorizontally(tween(260)) { fullWidth -> fullWidth / 10 }
                },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = {
                    fadeIn(tween(220)) +
                        slideInHorizontally(tween(260)) { fullWidth -> -fullWidth / 10 }
                },
                popExitTransition = {
                    fadeOut(tween(140)) +
                        slideOutHorizontally(tween(200)) { fullWidth -> fullWidth / 10 }
                },
            ) {
                composable(Destination.HOME.route) {
                    HomeScreen(
                        onPickFiles = onPickFiles,
                        onPickFolder = onPickFolder,
                        onOpenShares = { navigateTo(Destination.SHARES) },
                    )
                }
                composable(Destination.SHARES.route) {
                    SharesScreen(
                        onPickFiles = onPickFiles,
                        onPickFolder = onPickFolder,
                    )
                }
                composable(Destination.SETTINGS.route) {
                    SettingsScreen()
                }
                composable(Destination.DIAGNOSTICS.route) {
                    DiagnosticsScreen()
                }
                composable(Destination.ABOUT.route) {
                    AboutScreen()
                }
            }
        }

        val pendingKind =
            pendingKindName?.let { name ->
                runCatching { ShareKind.valueOf(name) }.getOrNull()
            }
        if (pendingKind != null) {
            val clearSelection = {
                pendingKindName = null
                pendingUris = arrayListOf()
            }
            CreateShareDialog(
                kind = pendingKind,
                selectedUris = pendingUris,
                defaultExpiry = settings.defaultExpiry,
                creating = creating,
                onAddFiles = { extra ->
                    pendingUris =
                        ArrayList(
                            (pendingUris + extra.map(Uri::toString)).distinct(),
                        )
                },
                onRemoveFile = { raw ->
                    pendingUris = ArrayList(pendingUris.filterNot { it == raw })
                },
                onDismiss = { if (!creating) clearSelection() },
                onConfirm = { name, expiry, password, maxDownloads ->
                    val uris = pendingUris.map(Uri::parse)
                    createViewModel.createShare(
                        uris = uris,
                        kind = pendingKind,
                        name = name,
                        expiry = expiry,
                        password = password,
                        maxDownloads = maxDownloads,
                    )
                    clearSelection()
                },
            )
        }

        message?.let { current ->
            AppMessageDialog(
                message = current,
                onDismiss = { message = null },
            )
        }

        whatsNew?.let { release ->
            WhatsNewDialog(
                release = release,
                onDismiss = whatsNewViewModel::dismiss,
            )
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
