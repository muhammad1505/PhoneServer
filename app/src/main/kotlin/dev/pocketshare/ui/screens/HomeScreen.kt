package dev.pocketshare.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketshare.R
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.core.model.ShareSummary
import dev.pocketshare.core.model.TunnelMode
import dev.pocketshare.data.settings.AppSettings
import dev.pocketshare.runtime.Reachability
import dev.pocketshare.runtime.SharingPhase
import dev.pocketshare.runtime.SharingState
import dev.pocketshare.service.SharingService
import dev.pocketshare.tunnel.TunnelStatus
import dev.pocketshare.ui.UiMessage
import dev.pocketshare.ui.UiTone
import dev.pocketshare.ui.components.BrandHeader
import dev.pocketshare.ui.components.CompactActionTile
import dev.pocketshare.ui.components.IconTile
import dev.pocketshare.ui.components.InfoCard
import dev.pocketshare.ui.components.LocalUiMessageHost
import dev.pocketshare.ui.components.QrDialog
import dev.pocketshare.ui.components.SectionHeader
import dev.pocketshare.ui.copyLink
import dev.pocketshare.ui.shareLink
import dev.pocketshare.ui.theme.LocalBrandColors
import dev.pocketshare.ui.viewmodel.HomeViewModel
import dev.pocketshare.ui.viewmodel.TrafficUsage
import java.util.Locale

@Composable
fun HomeScreen(
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenShares: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val showMessage = LocalUiMessageHost.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeShares by viewModel.activeShares.collectAsStateWithLifecycle()
    val traffic by viewModel.traffic.collectAsStateWithLifecycle()
    val brand = LocalBrandColors.current
    var qrLink by remember { mutableStateOf<String?>(null) }

    fun copy(link: String?) {
        if (link == null) {
            showMessage(UiMessage(R.string.share_copy_unavailable, UiTone.INFO))
            return
        }
        copyLink(context, link)
        showMessage(UiMessage(R.string.link_copied, UiTone.SUCCESS, R.string.link_copied_body))
    }

    fun share(link: String?) {
        if (link == null) {
            showMessage(
                UiMessage(
                    R.string.share_link_needs_server,
                    UiTone.INFO,
                    R.string.share_link_needs_server_body,
                ),
            )
        } else {
            shareLink(context, link)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            BrandHeader(subtitle = stringResource(R.string.app_tagline))
        }

        if (state.isSharing && network.online && !network.isWifi) {
            item { MobileDataBanner() }
        }

        item {
            HeroCard(
                state = state,
                settings = settings,
                online = network.online,
                traffic = traffic,
                onMobileData = network.online && !network.isWifi,
                onStart = { SharingService.start(context) },
                onStop = { SharingService.stop(context) },
                onCopy = ::copy,
                onShare = ::share,
                onShowQr = { link -> qrLink = link },
            )
        }

        item {
            SectionHeader(title = stringResource(R.string.home_quick_actions))
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompactActionTile(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.action_new_files),
                    subtitle = stringResource(R.string.home_action_files_desc),
                    onClick = onPickFiles,
                    modifier = Modifier.weight(1f),
                )
                CompactActionTile(
                    icon = Icons.Outlined.Folder,
                    title = stringResource(R.string.action_new_folder),
                    subtitle = stringResource(R.string.home_action_folder_desc),
                    onClick = onPickFolder,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.isSharing && !network.online) {
            item {
                InfoCard(
                    text = R.string.home_offline_warning,
                    icon = Icons.Outlined.WifiOff,
                    containerColor = brand.warningContainer,
                    contentColor = brand.onWarningContainer,
                )
            }
        }

        if (settings.tunnelMode == TunnelMode.QUICK) {
            item { InfoCard(text = R.string.home_quick_note, icon = Icons.Outlined.Bolt) }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.home_active_shares_title),
                trailing = {
                    TextButton(
                        onClick = onOpenShares,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.home_view_all))
                    }
                },
            )
        }

        if (activeShares.isEmpty()) {
            item { ActiveSharesEmpty() }
        } else {
            items(activeShares.take(3), key = { it.id }) { share ->
                ActiveShareRow(
                    share = share,
                    onCopy = { copy(viewModel.publicLink(share.token)) },
                    onShare = { share(viewModel.publicLink(share.token)) },
                    onShowQr = {
                        val link = viewModel.publicLink(share.token)
                        if (link == null) {
                            showMessage(UiMessage(R.string.share_link_unavailable, UiTone.INFO))
                        } else {
                            qrLink = link
                        }
                    },
                )
            }
            if (activeShares.size > 3) {
                item {
                    TextButton(
                        onClick = onOpenShares,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.home_more_shares, activeShares.size - 3))
                    }
                }
            }
        }
    }

    qrLink?.let { link ->
        QrDialog(link = link, onDismiss = { qrLink = null })
    }
}

@Composable
private fun HeroCard(
    state: SharingState,
    settings: AppSettings,
    online: Boolean,
    traffic: TrafficUsage,
    onMobileData: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String?) -> Unit,
    onShare: (String?) -> Unit,
    onShowQr: (String) -> Unit,
) {
    val brand = LocalBrandColors.current
    val sessionActive = state.phase == SharingPhase.RUNNING
    val degraded =
        sessionActive &&
            (
                state.tunnelStatus is TunnelStatus.Degraded ||
                    state.reachability == Reachability.UNREACHABLE
            )
    val running = sessionActive && !degraded
    val reconnecting = state.tunnelStatus as? TunnelStatus.Reconnecting
    val startingAttempt = (state.tunnelStatus as? TunnelStatus.Starting)?.attempt
    val error = state.phase == SharingPhase.ERROR
    val stopping = state.phase == SharingPhase.STOPPING
    val offline = state.isSharing && !online

    val transition = rememberInfiniteTransition(label = "heroStatus")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "heroStatusPulse",
    )

    val statusColor =
        when {
            offline -> brand.accentGold
            degraded -> brand.warning
            running -> brand.success
            error -> Color(0xFFFFB4AB)
            state.phase == SharingPhase.STARTING -> brand.accentGold
            else -> brand.heroContentMuted
        }
    val statusLabel =
        when {
            offline -> R.string.status_offline
            degraded -> R.string.status_degraded
            running -> R.string.status_live
            reconnecting != null -> R.string.status_reconnecting
            state.phase == SharingPhase.STARTING -> R.string.status_starting
            state.phase == SharingPhase.STOPPING -> R.string.status_stopping
            error -> R.string.status_error
            else -> R.string.status_not_running
        }
    val title =
        when {
            offline -> R.string.home_status_offline_title
            degraded -> R.string.home_status_degraded_title
            running -> R.string.home_status_running_title
            reconnecting != null -> R.string.home_status_reconnecting_title
            state.phase == SharingPhase.STARTING -> R.string.home_status_starting_title
            state.phase == SharingPhase.STOPPING -> R.string.home_status_stopping_title
            error -> R.string.home_status_error_title
            else -> R.string.home_status_stopped_title
        }
    val body =
        when {
            offline -> R.string.home_status_offline_body
            degraded -> R.string.home_status_degraded_body
            running -> R.string.home_status_running_body
            reconnecting != null -> R.string.home_status_reconnecting_body
            state.phase == SharingPhase.STARTING -> R.string.home_status_starting_body
            state.phase == SharingPhase.STOPPING -> R.string.home_status_stopping_body
            else -> R.string.home_status_stopped_body
        }
    val link = state.publicUrl

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(brand.heroGradient)),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val glowRadius = size.maxDimension * 0.72f
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(brand.glowA, Color.Transparent),
                        center = Offset(size.width * 0.98f, size.height * 0.02f),
                        radius = glowRadius,
                    ),
                radius = glowRadius,
                center = Offset(size.width * 0.98f, size.height * 0.02f),
            )
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(brand.glowB, Color.Transparent),
                        center = Offset(size.width * 0.02f, size.height * 0.98f),
                        radius = glowRadius * 0.72f,
                    ),
                radius = glowRadius * 0.72f,
                center = Offset(size.width * 0.02f, size.height * 0.98f),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroChip(
                    contentColor = brand.heroContent,
                    borderColor = brand.heroGlassBorder,
                    background = brand.heroGlass,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    statusColor.copy(
                                        alpha = if (running || degraded || reconnecting != null) pulse else 1f,
                                    ),
                                ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(statusLabel).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.8.sp,
                    )
                }
                Spacer(Modifier.width(6.dp))
                HeroChip(
                    contentColor = brand.heroContentMuted,
                    borderColor = brand.heroGlassBorder,
                    background = brand.heroGlass,
                ) {
                    Text(
                        text =
                            stringResource(
                                if (settings.tunnelMode == TunnelMode.NAMED) {
                                    R.string.named_tunnel_chip
                                } else {
                                    R.string.quick_tunnel_chip
                                },
                            ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.headlineSmall,
                color = brand.heroContent,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            )

            if (link != null && (sessionActive || settings.tunnelMode == TunnelMode.NAMED)) {
                Text(
                    text = stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = brand.heroContentMuted,
                )
                HeroLinkBox(link = link, onCopy = { onCopy(link) })
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HeroGlassAction(
                        icon = Icons.Filled.Share,
                        label = stringResource(R.string.action_share_link),
                        onClick = { onShare(link) },
                        modifier = Modifier.weight(1f),
                    )
                    HeroGlassAction(
                        icon = Icons.Outlined.QrCode2,
                        label = stringResource(R.string.action_show_qr),
                        onClick = { onShowQr(link) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (degraded) {
                    Text(
                        text =
                            (state.tunnelStatus as? TunnelStatus.Degraded)?.reason
                                ?: stringResource(R.string.home_status_degraded_body),
                        style = MaterialTheme.typography.labelSmall,
                        color = brand.heroContentMuted,
                    )
                } else if (reconnecting != null) {
                    ReconnectInfo(
                        attempt = reconnecting.attempt,
                        reason = reconnecting.reason,
                    )
                } else if (startingAttempt != null && startingAttempt > 1) {
                    ReconnectInfo(attempt = startingAttempt, reason = null)
                }
            } else {
                Text(
                    text =
                        if (error) {
                            state.error ?: stringResource(R.string.error_generic)
                        } else {
                            stringResource(body)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = brand.heroContentMuted,
                )
                if (error) {
                    Text(
                        text = stringResource(R.string.home_error_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = brand.heroContentMuted,
                    )
                }
                if (reconnecting != null || (state.phase == SharingPhase.STARTING && startingAttempt != null && startingAttempt > 1)) {
                    ReconnectInfo(
                        attempt = reconnecting?.attempt ?: startingAttempt ?: 1,
                        reason = reconnecting?.reason,
                    )
                }
            }

            if (sessionActive && traffic.available) {
                SessionTrafficStrip(traffic = traffic, onMobileData = onMobileData)
            }

            Button(
                onClick = { if (state.isSharing) onStop() else onStart() },
                enabled = state.phase != SharingPhase.STOPPING,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                shape = RoundedCornerShape(14.dp),
                border = if (state.isSharing || stopping) BorderStroke(1.dp, brand.heroGlassBorder) else null,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = if (state.isSharing || stopping) brand.heroGlass else brand.accentGold,
                        contentColor = if (state.isSharing || stopping) brand.heroContent else Color(0xFF2B1A00),
                        disabledContainerColor = brand.heroGlass.copy(alpha = 0.5f),
                        disabledContentColor = brand.heroContentMuted,
                    ),
            ) {
                Icon(
                    imageVector = if (state.isSharing || stopping) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(if (state.isSharing || stopping) R.string.action_stop else R.string.action_start),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (state.localPort != null) {
                Text(
                    text =
                        (
                            if (settings.tunnelMode == TunnelMode.NAMED) {
                                stringResource(R.string.named_tunnel_chip)
                            } else {
                                stringResource(R.string.quick_tunnel_chip)
                            }
                        ) + " · " + stringResource(R.string.home_local_port, state.localPort ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = brand.heroContentMuted,
                )
            }
        }
    }
}

@Composable
private fun MobileDataBanner() {
    val brand = LocalBrandColors.current
    val bannerBrush =
        Brush.horizontalGradient(
            listOf(brand.warningContainer, brand.warningContainer.copy(alpha = 0.62f)),
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(bannerBrush)
                .border(1.dp, brand.warning.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(brand.warning.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.SignalCellularAlt,
                contentDescription = null,
                tint = brand.warning,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = stringResource(R.string.home_mobile_banner),
            style = MaterialTheme.typography.labelMedium,
            color = brand.onWarningContainer,
            maxLines = 1,
            modifier =
                Modifier
                    .weight(1f)
                    .basicMarquee(initialDelayMillis = 1_200, repeatDelayMillis = 1_400),
        )
    }
}

@Composable
private fun SessionTrafficStrip(
    traffic: TrafficUsage,
    onMobileData: Boolean,
) {
    val brand = LocalBrandColors.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(brand.heroGlass)
                .border(1.dp, brand.heroGlassBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_traffic_caption).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = brand.heroContentMuted,
                letterSpacing = 0.9.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(if (onMobileData) R.string.home_traffic_mobile else R.string.home_traffic_wifi),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (onMobileData) brand.accentGold else brand.heroContentMuted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrafficStat(
                icon = Icons.Filled.ArrowUpward,
                label = stringResource(R.string.home_traffic_out),
                value = formatTraffic(traffic.sentBytes),
                tint = brand.accentGold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 10.dp)
                        .width(1.dp)
                        .height(22.dp)
                        .background(brand.heroGlassBorder),
            )
            TrafficStat(
                icon = Icons.Filled.ArrowDownward,
                label = stringResource(R.string.home_traffic_in),
                value = formatTraffic(traffic.receivedBytes),
                tint = brand.success,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrafficStat(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val brand = LocalBrandColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = brand.heroContentMuted,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = brand.heroContent,
            maxLines = 1,
        )
    }
}

/** Compact, locale-aware byte counter for the session meter ("9,7 MB"). */
internal fun formatTraffic(bytes: Long): String {
    if (bytes < 1_024L) return "$bytes B"
    val kb = bytes / 1_024.0
    if (kb < 1_024.0) return String.format(Locale.getDefault(), "%.0f KB", kb)
    val mb = kb / 1_024.0
    return when {
        mb < 10.0 -> String.format(Locale.getDefault(), "%.2f MB", mb)
        mb < 100.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        mb < 1_024.0 -> String.format(Locale.getDefault(), "%.0f MB", mb)
        else -> String.format(Locale.getDefault(), "%.2f GB", mb / 1_024.0)
    }
}

@Composable
private fun HeroChip(
    contentColor: Color,
    borderColor: Color,
    background: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        color = background,
        contentColor = contentColor,
        shape = CircleShape,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun HeroLinkBox(
    link: String,
    onCopy: () -> Unit,
) {
    val brand = LocalBrandColors.current
    Surface(
        color = brand.heroGlass,
        contentColor = brand.heroContent,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, brand.heroGlassBorder),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp, end = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_public_url).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = brand.heroContentMuted,
                    letterSpacing = 0.7.sp,
                )
                SelectionContainer {
                    Text(
                        text = link,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = brand.heroContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy_link),
                    tint = brand.heroContent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroGlassAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = LocalBrandColors.current
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(brand.heroGlass)
                .border(1.dp, brand.heroGlassBorder, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = brand.heroContent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = brand.heroContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReconnectInfo(
    attempt: Int,
    reason: String?,
) {
    val brand = LocalBrandColors.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(brand.heroGlass)
                .border(1.dp, brand.heroGlassBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = brand.accentGold,
            )
            Text(
                text = stringResource(R.string.home_reconnect_attempt, attempt),
                style = MaterialTheme.typography.labelMedium,
                color = brand.heroContent,
            )
        }
        if (!reason.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.home_reconnect_detail, reason.take(180)),
                style = MaterialTheme.typography.bodySmall,
                color = brand.heroContentMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActiveSharesEmpty() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = Icons.Outlined.FolderOpen,
                size = 36.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = stringResource(R.string.home_no_active_shares),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.home_start_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActiveShareRow(
    share: ShareSummary,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onShowQr: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = if (share.kind == ShareKind.FOLDER) Icons.Outlined.Folder else Icons.Outlined.Description,
                size = 36.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = share.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val label =
                    if (share.kind == ShareKind.FOLDER) {
                        stringResource(R.string.share_kind_folder)
                    } else {
                        pluralStringResource(R.plurals.share_file_count, share.fileCount, share.fileCount)
                    }
                val passwordSuffix =
                    if (share.hasPassword) {
                        " · " + stringResource(R.string.share_password_short)
                    } else {
                        ""
                    }
                Text(
                    text = label + passwordSuffix,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy_link),
                    modifier = Modifier.size(17.dp),
                )
            }
            IconButton(onClick = onShowQr, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Outlined.QrCode2,
                    contentDescription = stringResource(R.string.action_show_qr),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.action_share_link),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}
