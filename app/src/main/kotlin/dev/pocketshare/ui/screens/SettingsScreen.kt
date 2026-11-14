package dev.pocketshare.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketshare.R
import dev.pocketshare.core.model.AppLanguage
import dev.pocketshare.core.model.ShareExpiry
import dev.pocketshare.core.model.TunnelMode
import dev.pocketshare.core.util.HostnameValidator
import dev.pocketshare.ui.UiMessage
import dev.pocketshare.ui.UiTone
import dev.pocketshare.ui.components.GroupCard
import dev.pocketshare.ui.components.GroupDivider
import dev.pocketshare.ui.components.IconTile
import dev.pocketshare.ui.components.LocalUiMessageHost
import dev.pocketshare.ui.components.ScreenHeader
import dev.pocketshare.ui.components.expiryLabel
import dev.pocketshare.ui.theme.LocalBrandColors
import dev.pocketshare.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val showMessage = LocalUiMessageHost.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val namedTunnel by viewModel.namedTunnel.collectAsStateWithLifecycle()
    val batteryUnrestricted by viewModel.batteryUnrestricted.collectAsStateWithLifecycle()
    val brand = LocalBrandColors.current

    var selectedMode by rememberSaveable { mutableStateOf(settings.tunnelMode) }
    var portText by rememberSaveable {
        mutableStateOf(
            settings.port
                .takeIf { it > 0 }
                ?.toString()
                .orEmpty(),
        )
    }
    var hostname by rememberSaveable(namedTunnel.hostname) { mutableStateOf(namedTunnel.hostname) }
    var tokenInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(settings.tunnelMode, settings.port) {
        selectedMode = settings.tunnelMode
        portText =
            settings.port
                .takeIf { it > 0 }
                ?.toString()
                .orEmpty()
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            if (message.textRes == R.string.settings_saved) tokenInput = ""
            showMessage(message)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshBatteryStatus()
    }

    val previewHostname =
        HostnameValidator.normalize(hostname.ifBlank { namedTunnel.hostname })
    val previewPort = portText.toIntOrNull()?.takeIf { it in 1..65_535 } ?: 8080

    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val payload = viewModel.exportSettings()
                val written =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                stream.write(payload.toByteArray())
                            } ?: error("Unable to open the chosen file")
                        }.isSuccess
                    }
                showMessage(
                    if (written) {
                        UiMessage(R.string.settings_exported, UiTone.SUCCESS, R.string.settings_exported_body)
                    } else {
                        UiMessage(R.string.settings_export_failed, UiTone.ERROR, R.string.settings_export_failed_body)
                    },
                )
            }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val raw =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver
                                .openInputStream(uri)
                                ?.bufferedReader()
                                ?.use { it.readText() }
                        }.getOrNull()
                    }
                if (raw == null) {
                    showMessage(
                        UiMessage(R.string.settings_import_failed, UiTone.ERROR, R.string.settings_import_failed_body),
                    )
                } else {
                    viewModel.importSettings(raw)
                }
            }
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
                action = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.settings_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_export)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.FileUpload, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("pocketshare-settings.json")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_import)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain"))
                                },
                            )
                        }
                    }
                },
            )
        }

        item {
            SettingsGroup(
                icon = Icons.Outlined.Cloud,
                title = stringResource(R.string.settings_tunnel),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ModeSegment(
                        mode = TunnelMode.QUICK,
                        selected = selectedMode == TunnelMode.QUICK,
                        onClick = { selectedMode = TunnelMode.QUICK },
                        modifier = Modifier.weight(1f),
                    )
                    ModeSegment(
                        mode = TunnelMode.NAMED,
                        selected = selectedMode == TunnelMode.NAMED,
                        onClick = {
                            selectedMode = TunnelMode.NAMED
                            if (portText.isBlank()) portText = "8080"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                GroupDivider(startPadding = 12.dp)

                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { input ->
                            portText = input.filter { it.isDigit() }.take(5)
                        },
                        label = { Text(stringResource(R.string.settings_port)) },
                        placeholder = { Text(stringResource(R.string.settings_port_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            Text(
                                text =
                                    if (selectedMode == TunnelMode.NAMED) {
                                        stringResource(R.string.settings_domain_service_hint, previewPort)
                                    } else {
                                        stringResource(R.string.settings_port_desc)
                                    },
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (selectedMode == TunnelMode.NAMED) {
                        OutlinedTextField(
                            value = hostname,
                            onValueChange = { hostname = it },
                            label = { Text(stringResource(R.string.settings_hostname)) },
                            placeholder = { Text(stringResource(R.string.settings_hostname_hint)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text(stringResource(R.string.settings_token)) },
                            placeholder = { Text(stringResource(R.string.settings_token_hint)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            visualTransformation = PasswordVisualTransformation(),
                            supportingText = {
                                Text(
                                    text =
                                        stringResource(
                                            if (namedTunnel.hasToken) {
                                                R.string.settings_token_keep_saved
                                            } else {
                                                R.string.settings_token_hint
                                            },
                                        ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DomainStatusRow(
                            hostname = previewHostname,
                            hasToken = namedTunnel.hasToken || tokenInput.isNotBlank(),
                            port = previewPort,
                            mode = selectedMode,
                        )
                        if (namedTunnel.hasToken) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = brand.success,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_token_saved),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { viewModel.clearNamedToken() }) {
                                    Text(stringResource(R.string.action_clear_token))
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.settings_named_not_configured),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (previewHostname == null || !(namedTunnel.hasToken || tokenInput.isNotBlank())) {
                            DomainSetupNote()
                        }
                    } else {
                        if (namedTunnel.hostname.isBlank()) {
                            DomainTeaserRow(
                                onSetUp = {
                                    selectedMode = TunnelMode.NAMED
                                    if (portText.isBlank()) portText = "8080"
                                },
                            )
                        } else {
                            DomainStatusRow(
                                hostname = previewHostname,
                                hasToken = namedTunnel.hasToken,
                                port = previewPort,
                                mode = selectedMode,
                            )
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.saveTunnelDetails(
                                mode = selectedMode,
                                portText = portText,
                                hostnameInput = hostname,
                                tokenInput = tokenInput,
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text =
                                stringResource(
                                    if (selectedMode == TunnelMode.NAMED) {
                                        R.string.settings_save_named
                                    } else {
                                        R.string.action_save
                                    },
                                ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        item {
            SettingsGroup(
                icon = Icons.Outlined.Wifi,
                title = stringResource(R.string.settings_connection),
            ) {
                SwitchRow(
                    icon = Icons.Outlined.Wifi,
                    title = stringResource(R.string.settings_wifi_only),
                    description = stringResource(R.string.settings_wifi_only_desc),
                    checked = settings.wifiOnly,
                    onCheckedChange = viewModel::setWifiOnly,
                )
                GroupDivider(startPadding = 40.dp)
                SwitchRow(
                    icon = Icons.Outlined.Lan,
                    title = stringResource(R.string.settings_lan_access),
                    description = stringResource(R.string.settings_lan_access_desc),
                    checked = settings.allowLanAccess,
                    onCheckedChange = viewModel::setLanAccess,
                )
                GroupDivider(startPadding = 40.dp)
                ChoiceRow(
                    icon = Icons.Outlined.Timer,
                    label = stringResource(R.string.settings_auto_stop),
                    description = stringResource(R.string.settings_auto_stop_desc),
                    value =
                        if (settings.autoStopMinutes <= 0) {
                            stringResource(R.string.settings_auto_stop_off)
                        } else {
                            stringResource(R.string.settings_auto_stop_minutes, settings.autoStopMinutes)
                        },
                    options =
                        AUTO_STOP_OPTIONS.map { minutes ->
                            minutes to
                                if (minutes <= 0) {
                                    stringResource(R.string.settings_auto_stop_off)
                                } else {
                                    stringResource(R.string.settings_auto_stop_minutes, minutes)
                                }
                        },
                    onSelect = viewModel::setAutoStopMinutes,
                )
            }
        }

        item {
            SettingsGroup(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.settings_defaults),
            ) {
                ChoiceRow(
                    icon = Icons.Outlined.Timer,
                    label = stringResource(R.string.settings_default_expiry),
                    value = stringResource(expiryLabel(settings.defaultExpiry)),
                    options =
                        ShareExpiry.entries.map { expiry ->
                            expiry to stringResource(expiryLabel(expiry))
                        },
                    onSelect = viewModel::setDefaultExpiry,
                )
            }
        }

        item {
            SettingsGroup(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_general),
            ) {
                ChoiceRow(
                    icon = Icons.Outlined.Language,
                    label = stringResource(R.string.settings_language),
                    description = stringResource(R.string.settings_language_desc),
                    value = stringResource(languageLabel(settings.language)),
                    options =
                        AppLanguage.entries.map { language ->
                            language to stringResource(languageLabel(language))
                        },
                    onSelect = viewModel::setLanguage,
                )
            }
        }

        item {
            SettingsGroup(
                icon = Icons.Outlined.BatteryStd,
                title = stringResource(R.string.settings_battery),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    IconTile(
                        icon = Icons.Outlined.BatteryStd,
                        size = 34.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor =
                            if (batteryUnrestricted) {
                                brand.success
                            } else {
                                brand.onWarningContainer
                            },
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text =
                                    stringResource(
                                        if (batteryUnrestricted) {
                                            R.string.battery_optimized
                                        } else {
                                            R.string.battery_not_optimized
                                        },
                                    ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                shape = CircleShape,
                                color =
                                    if (batteryUnrestricted) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        brand.warningContainer
                                    },
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (batteryUnrestricted) {
                                                    brand.success
                                                } else {
                                                    brand.warning
                                                },
                                            ),
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.settings_battery_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                if (!openBatterySettings(context, context.packageName)) {
                                    showMessage(
                                        UiMessage(R.string.settings_battery_open_failed, UiTone.ERROR),
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.settings_battery_button))
                        }
                    }
                }
            }
        }

        item {
            CompactNote(
                icon = Icons.Outlined.Shield,
                text = stringResource(R.string.settings_security_note),
            )
        }

        item {
            CompactNote(
                icon = Icons.Outlined.Cloud,
                text = stringResource(R.string.settings_cloudflare_notice),
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp,
            )
        }
        GroupCard {
            content()
        }
    }
}

@Composable
private fun ModeSegment(
    mode: TunnelMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        border =
            BorderStroke(
                1.dp,
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (mode == TunnelMode.QUICK) Icons.Outlined.Bolt else Icons.Outlined.Language,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text =
                        stringResource(
                            if (mode == TunnelMode.QUICK) {
                                R.string.tunnel_mode_quick
                            } else {
                                R.string.tunnel_mode_named
                            },
                        ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text =
                    stringResource(
                        if (mode == TunnelMode.QUICK) {
                            R.string.tunnel_mode_quick_desc
                        } else {
                            R.string.tunnel_mode_named_desc
                        },
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DomainStatusRow(
    hostname: String?,
    hasToken: Boolean,
    port: Int,
    mode: TunnelMode,
) {
    val brand = LocalBrandColors.current
    val ready = hostname != null && hasToken
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Language,
            contentDescription = null,
            tint = if (ready) brand.success else brand.onWarningContainer,
            modifier =
                Modifier
                    .padding(top = 1.dp)
                    .size(16.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text =
                    stringResource(
                        if (ready) R.string.settings_domain_ready else R.string.settings_domain_pending,
                    ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    if (hostname != null) {
                        stringResource(R.string.settings_domain_preview, "https://$hostname")
                    } else {
                        stringResource(R.string.settings_domain_missing_hostname)
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ready) {
                Text(
                    text = stringResource(R.string.settings_domain_service_hint, port),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mode != TunnelMode.NAMED) {
                    Text(
                        text = stringResource(R.string.settings_domain_quick_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (hostname != null && !hasToken) {
                Text(
                    text = stringResource(R.string.settings_domain_missing_token),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DomainTeaserRow(onSetUp: () -> Unit) {
    val brand = LocalBrandColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onSetUp)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Language,
            contentDescription = null,
            tint = brand.onWarningContainer,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = stringResource(R.string.settings_domain_help_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.settings_domain_help_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_domain_setup_action),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DomainSetupNote() {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_domain_help_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            DomainStep(number = 1, text = stringResource(R.string.settings_domain_help_step1))
            DomainStep(number = 2, text = stringResource(R.string.settings_domain_help_step2))
            DomainStep(number = 3, text = stringResource(R.string.settings_domain_help_step3))
            Text(
                text = stringResource(R.string.settings_domain_cloudflare_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DomainStep(
    number: Int,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> ChoiceRow(
    icon: ImageVector,
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    description: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description ?: value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (option, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun CompactNote(
    icon: ImageVector,
    text: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(top = 1.dp)
                    .size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun languageLabel(language: AppLanguage): Int =
    when (language) {
        AppLanguage.SYSTEM -> R.string.language_system
        AppLanguage.ENGLISH -> R.string.language_english
        AppLanguage.INDONESIAN -> R.string.language_indonesian
    }

private val AUTO_STOP_OPTIONS = listOf(0, 15, 30, 60, 120, 240, 480)

/**
 * Opens the exemption flow for this app first: some OEM battery screens omit sideloaded apps from
 * the generic optimization list, while the per-app request always names Pocket Share.
 */
private fun openBatterySettings(
    context: Context,
    packageName: String,
): Boolean {
    val packageUri = Uri.parse("package:$packageName")
    val intents =
        listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
    return intents.any { intent -> runCatching { context.startActivity(intent) }.isSuccess }
}
