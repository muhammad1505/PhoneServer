package dev.pocketshare.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketshare.R
import dev.pocketshare.core.model.TunnelMode
import dev.pocketshare.runtime.Reachability
import dev.pocketshare.runtime.SharingPhase
import dev.pocketshare.runtime.SharingState
import dev.pocketshare.tunnel.TunnelStatus
import dev.pocketshare.ui.components.ConfirmDialog
import dev.pocketshare.ui.components.GroupCard
import dev.pocketshare.ui.components.GroupDivider
import dev.pocketshare.ui.components.LocalUiMessageHost
import dev.pocketshare.ui.components.MetricTile
import dev.pocketshare.ui.components.ScreenHeader
import dev.pocketshare.ui.components.SectionHeader
import dev.pocketshare.ui.components.StatusPill
import dev.pocketshare.ui.theme.LocalBrandColors
import dev.pocketshare.ui.viewmodel.DiagnosticsFacts
import dev.pocketshare.ui.viewmodel.DiagnosticsViewModel
import java.util.Locale

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val showMessage = LocalUiMessageHost.current
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val activeShares by viewModel.activeShares.collectAsStateWithLifecycle()
    val exportFileName = stringResource(R.string.diag_log_filename)
    var clearConfirm by remember { mutableStateOf(false) }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    val logScroll = rememberScrollState()

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri != null) viewModel.export(uri)
        }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect(showMessage)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    LaunchedEffect(log, autoScroll) {
        if (autoScroll) {
            logScroll.animateScrollTo(logScroll.maxValue)
        }
    }

    val health = healthItems(runtimeState, facts)
    val attention = health.count { !it.ok }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.diag_title),
                subtitle = stringResource(R.string.diag_subtitle),
            )
        }

        item {
            SectionHeader(title = stringResource(R.string.diag_overview))
        }

        item {
            RuntimeOverview(state = runtimeState)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    icon = Icons.Outlined.Speed,
                    label = stringResource(R.string.diag_metric_tunnel),
                    value = compactTunnelLabel(runtimeState.tunnelStatus),
                    modifier = Modifier.weight(1f),
                    tone =
                        when (runtimeState.tunnelStatus) {
                            is TunnelStatus.Running -> LocalBrandColors.current.success
                            is TunnelStatus.Degraded -> LocalBrandColors.current.warning
                            is TunnelStatus.Failed -> MaterialTheme.colorScheme.error
                            else -> LocalBrandColors.current.warning
                        },
                )
                MetricTile(
                    icon = Icons.Outlined.SettingsEthernet,
                    label = stringResource(R.string.diag_metric_port),
                    value = runtimeState.localPort?.toString() ?: "-",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    icon = Icons.Outlined.Link,
                    label = stringResource(R.string.diag_metric_shares),
                    value = activeShares.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    icon = Icons.Outlined.Timer,
                    label = stringResource(R.string.diag_metric_uptime),
                    value = uptimeText(runtimeState, now),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.diag_health),
                trailing = {
                    StatusPill(
                        text =
                            if (attention == 0) {
                                stringResource(R.string.diag_health_ok)
                            } else {
                                stringResource(R.string.diag_health_attention, attention)
                            },
                        containerColor =
                            if (attention == 0) {
                                LocalBrandColors.current.success.copy(alpha = 0.14f)
                            } else {
                                LocalBrandColors.current.warningContainer
                            },
                        contentColor =
                            if (attention == 0) {
                                LocalBrandColors.current.success
                            } else {
                                LocalBrandColors.current.onWarningContainer
                            },
                    )
                },
            )
        }

        item {
            GroupCard {
                health.forEachIndexed { index, item ->
                    if (index > 0) GroupDivider(startPadding = 12.dp)
                    HealthRow(item = item)
                }
            }
        }

        item {
            SectionHeader(title = stringResource(R.string.diag_system))
        }

        item {
            GroupCard {
                FactRow(R.string.diag_build, facts.appVersion, monospace = true)
                GroupDivider(startPadding = 12.dp)
                FactRow(
                    R.string.diag_version,
                    facts.cloudflaredVersion,
                    monospace = true,
                )
                GroupDivider(startPadding = 12.dp)
                FactRow(R.string.diag_dns, facts.dnsServers, monospace = true, maxLines = 2)
                GroupDivider(startPadding = 12.dp)
                FactRow(R.string.diag_binary, facts.binaryPath ?: "-", monospace = true)
                GroupDivider(startPadding = 12.dp)
                FactRow(R.string.diag_log_size, formatBytes(facts.logSizeBytes))
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.diag_logs_live),
                trailing = {
                    LiveChip(live = live)
                },
            )
        }

        item {
            GroupCard {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            stringResource(
                                if (live) R.string.diag_live_hint else R.string.diag_paused_hint,
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::refresh, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.setLive(!live) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (live) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription =
                                stringResource(
                                    if (live) R.string.action_pause else R.string.action_resume,
                                ),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { exportLauncher.launch(exportFileName) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = stringResource(R.string.action_export),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { clearConfirm = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.action_clear),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = autoScroll,
                        onClick = { autoScroll = !autoScroll },
                        label = {
                            Text(
                                text = stringResource(R.string.diag_auto_scroll),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }

                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .height(238.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                ) {
                    SelectionContainer {
                        Text(
                            text = log,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(logScroll)
                                    .padding(10.dp),
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .padding(top = 1.dp)
                            .size(14.dp),
                )
                Text(
                    text = stringResource(R.string.diag_no_secrets),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (clearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.diag_clear_confirm_title),
            body = stringResource(R.string.diag_clear_confirm_body),
            confirmLabel = stringResource(R.string.action_clear),
            onConfirm = {
                clearConfirm = false
                viewModel.clearLog()
            },
            onDismiss = { clearConfirm = false },
            icon = Icons.Outlined.Delete,
            destructive = true,
        )
    }
}

@Composable
private fun RuntimeOverview(state: SharingState) {
    val brand = LocalBrandColors.current
    val running = state.phase == SharingPhase.RUNNING
    val detail =
        when (val status = state.tunnelStatus) {
            is TunnelStatus.Reconnecting -> status.reason
            is TunnelStatus.Degraded -> status.reason
            is TunnelStatus.Failed -> status.reason
            else -> state.error
        }

    GroupCard {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.tunnelStatus is TunnelStatus.Degraded ||
                                        state.reachability == Reachability.UNREACHABLE -> brand.warning

                                    running -> brand.success
                                    state.phase == SharingPhase.ERROR -> MaterialTheme.colorScheme.error
                                    state.phase == SharingPhase.STARTING -> brand.warning
                                    else -> MaterialTheme.colorScheme.outline
                                },
                            ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = phaseLabel(state.phase),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (state.phase != SharingPhase.STOPPED) {
                    StatusPill(
                        text = tunnelLabel(state.tunnelStatus),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text =
                    if (state.mode == TunnelMode.NAMED) {
                        stringResource(R.string.named_tunnel_chip)
                    } else {
                        stringResource(R.string.quick_tunnel_chip)
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.publicUrl != null) {
                GroupDivider(startPadding = 0.dp)
                SelectionContainer {
                    Text(
                        text = state.publicUrl,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class HealthItem(
    val icon: ImageVector,
    val label: Int,
    val value: String,
    val ok: Boolean,
)

@Composable
private fun healthItems(
    state: SharingState,
    facts: DiagnosticsFacts,
): List<HealthItem> =
    listOf(
        HealthItem(
            icon = Icons.Outlined.CheckCircle,
            label = R.string.diag_health_binary,
            value =
                stringResource(
                    if (facts.binaryPresent) R.string.diag_health_ok_detail else R.string.diag_health_missing,
                ),
            ok = facts.binaryPresent,
        ),
        HealthItem(
            icon = Icons.Outlined.CheckCircle,
            label = R.string.diag_health_ca,
            value =
                stringResource(
                    if (facts.caBundlePresent) R.string.diag_health_ok_detail else R.string.diag_health_missing,
                ),
            ok = facts.caBundlePresent,
        ),
        HealthItem(
            icon = Icons.Outlined.CheckCircle,
            label = R.string.diag_health_notifications,
            value = stringResource(if (facts.notificationsEnabled) R.string.diag_yes else R.string.diag_no),
            ok = facts.notificationsEnabled,
        ),
        HealthItem(
            icon = Icons.Outlined.CheckCircle,
            label = R.string.diag_health_battery,
            value =
                stringResource(
                    if (facts.batteryUnrestricted) {
                        R.string.diag_health_unrestricted
                    } else {
                        R.string.diag_health_restricted
                    },
                ),
            ok = facts.batteryUnrestricted,
        ),
        HealthItem(
            icon = Icons.Outlined.CheckCircle,
            label = R.string.diag_health_tunnel,
            value = compactTunnelLabel(state.tunnelStatus),
            ok = state.tunnelStatus is TunnelStatus.Running,
        ),
        HealthItem(
            icon = Icons.Outlined.CheckCircle,
            label = R.string.diag_health_port,
            value = state.localPort?.toString() ?: stringResource(R.string.diag_health_unavailable),
            ok = state.localPort != null,
        ),
    )

@Composable
private fun HealthRow(item: HealthItem) {
    val brand = LocalBrandColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.ok) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = if (item.ok) brand.success else brand.warning,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(item.label),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = item.value,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (item.ok) {
                    brand.success
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
        )
    }
}

@Composable
private fun FactRow(
    label: Int,
    value: String,
    monospace: Boolean = false,
    maxLines: Int = 1,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.End,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f),
        )
    }
}

@Composable
private fun LiveChip(live: Boolean) {
    val brand = LocalBrandColors.current
    val color = if (live) brand.success else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color),
            )
            Text(
                text = stringResource(if (live) R.string.diag_live else R.string.diag_paused),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun phaseLabel(phase: SharingPhase): String =
    stringResource(
        when (phase) {
            SharingPhase.RUNNING -> R.string.diag_running
            SharingPhase.STARTING,
            SharingPhase.STOPPING,
            -> R.string.home_status_starting_title

            SharingPhase.ERROR -> R.string.home_status_error_title
            SharingPhase.STOPPED -> R.string.diag_stopped
        },
    )

@Composable
private fun tunnelLabel(status: TunnelStatus): String =
    when (status) {
        is TunnelStatus.Stopped -> stringResource(R.string.diag_tunnel_stopped)
        is TunnelStatus.Starting ->
            stringResource(R.string.diag_tunnel_connecting, status.attempt)

        is TunnelStatus.Running -> stringResource(R.string.diag_tunnel_connected)
        is TunnelStatus.Degraded -> stringResource(R.string.diag_tunnel_degraded)
        is TunnelStatus.Reconnecting ->
            stringResource(
                R.string.diag_tunnel_reconnecting,
                (status.delayMillis / 1_000L).coerceAtLeast(1L).toInt(),
                status.attempt,
            )

        is TunnelStatus.Failed -> stringResource(R.string.diag_tunnel_failed)
    }

@Composable
private fun compactTunnelLabel(status: TunnelStatus): String =
    when (status) {
        is TunnelStatus.Running -> stringResource(R.string.diag_tunnel_connected)
        is TunnelStatus.Degraded -> stringResource(R.string.status_degraded)
        is TunnelStatus.Starting -> stringResource(R.string.status_starting)
        is TunnelStatus.Reconnecting -> stringResource(R.string.status_reconnecting)
        is TunnelStatus.Failed -> stringResource(R.string.status_error)
        is TunnelStatus.Stopped -> stringResource(R.string.diag_stopped)
    }

@Composable
private fun uptimeText(
    state: SharingState,
    now: Long,
): String {
    val started = state.startedAtEpochMs
    if (started == null || state.phase == SharingPhase.STOPPED || state.phase == SharingPhase.ERROR) {
        return stringResource(R.string.diag_uptime_none)
    }
    val seconds = ((now - started) / 1_000L).coerceAtLeast(0L)
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    return when {
        hours > 0 -> stringResource(R.string.diag_uptime_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.diag_uptime_minutes_seconds, minutes, seconds % 60L)
        else -> stringResource(R.string.diag_uptime_seconds, seconds)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    return String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
}
