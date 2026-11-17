package dev.pocketshare.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketshare.R
import dev.pocketshare.data.traffic.TrafficBucket
import dev.pocketshare.ui.theme.LocalBrandColors
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId

/**
 * Network usage history for the live traffic strip. Buckets are loaded only for the selected
 * range, so the daily tab does not replay years of samples.
 */
@Composable
fun TrafficHistoryDialog(
    loadHistory: (Long) -> Flow<List<TrafficBucket>>,
    onDismiss: () -> Unit,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val nowEpochMs = remember { System.currentTimeMillis() }
    var range by rememberSaveable { mutableStateOf(TrafficRange.DAY) }
    val historyFlow =
        remember(range, nowEpochMs, zoneId) {
            loadHistory(range.sinceEpochMs(nowEpochMs, zoneId))
        }
    val buckets by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val series =
        remember(buckets, range, nowEpochMs, zoneId) {
            buildTrafficSeries(
                buckets = buckets,
                range = range,
                nowEpochMs = nowEpochMs,
                zoneId = zoneId,
            )
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(icon = Icons.Outlined.BarChart, size = 42.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = stringResource(R.string.traffic_chart_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(R.string.traffic_chart_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                    }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TrafficRange.entries.forEach { candidate ->
                            FilterChip(
                                selected = range == candidate,
                                onClick = { range = candidate },
                                label = {
                                    Text(
                                        text = stringResource(candidate.tabLabelRes()),
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }

                    TrafficChartCard(series = series, range = range)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricTile(
                            icon = Icons.Filled.ArrowUpward,
                            label = stringResource(R.string.traffic_chart_out),
                            value = formatTraffic(series.sentBytes),
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            icon = Icons.Filled.ArrowDownward,
                            label = stringResource(R.string.traffic_chart_in),
                            value = formatTraffic(series.receivedBytes),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Text(
                        text = stringResource(R.string.traffic_chart_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onDismiss,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.action_done),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficChartCard(
    series: TrafficSeries,
    range: TrafficRange,
) {
    val brand = LocalBrandColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(range.rangeLabelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (!series.isEmpty) {
                    Text(
                        text = stringResource(R.string.traffic_chart_peak, formatTraffic(series.peakBytes)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (series.isEmpty) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.traffic_chart_empty_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.traffic_chart_empty_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                TrafficBars(
                    series = series,
                    sentColor = brand.accentGold,
                    receivedColor = brand.success,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    labelIndices(series.points.size).forEach { index ->
                        Text(
                            text = series.points[index].label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TrafficLegend(color = brand.accentGold, label = stringResource(R.string.traffic_chart_out))
                TrafficLegend(color = brand.success, label = stringResource(R.string.traffic_chart_in))
            }
        }
    }
}

@Composable
private fun TrafficBars(
    series: TrafficSeries,
    sentColor: Color,
    receivedColor: Color,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(150.dp),
    ) {
        val points = series.points
        if (points.isEmpty()) return@Canvas
        val maxValue = series.peakBytes.coerceAtLeast(1L).toFloat()
        val slot = size.width / points.size
        val barWidth = (slot * 0.28f).coerceIn(2.dp.toPx(), 14.dp.toPx())
        val barGap = (barWidth * 0.35f).coerceAtLeast(1.dp.toPx())
        val groupWidth = barWidth * 2 + barGap
        val chartHeight = size.height

        for (fraction in listOf(0.5f, 1f)) {
            val y = chartHeight * (1f - fraction)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }

        points.forEachIndexed { index, point ->
            val center = slot * index + slot / 2f
            val sentHeight = (point.sentBytes / maxValue) * chartHeight
            val receivedHeight = (point.receivedBytes / maxValue) * chartHeight
            if (sentHeight > 0f) {
                drawRoundRect(
                    color = sentColor,
                    topLeft = Offset(center - groupWidth / 2f, chartHeight - sentHeight),
                    size = Size(barWidth, sentHeight),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
            if (receivedHeight > 0f) {
                drawRoundRect(
                    color = receivedColor,
                    topLeft = Offset(center - groupWidth / 2f + barWidth + barGap, chartHeight - receivedHeight),
                    size = Size(barWidth, receivedHeight),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
    }
}

@Composable
private fun TrafficLegend(
    color: Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = color,
        ) {}
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun labelIndices(size: Int): List<Int> =
    when {
        size <= 7 -> (0 until size).toList()
        size <= 12 -> listOf(0, 2, 4, 6, 8, 10, size - 1)
        else -> listOf(0, size / 4, size / 2, size * 3 / 4, size - 1)
    }

private fun TrafficRange.tabLabelRes(): Int =
    when (this) {
        TrafficRange.DAY -> R.string.traffic_chart_tab_hours
        TrafficRange.WEEK -> R.string.traffic_chart_tab_days
        TrafficRange.MONTH -> R.string.traffic_chart_tab_months
        TrafficRange.YEAR -> R.string.traffic_chart_tab_years
    }

private fun TrafficRange.rangeLabelRes(): Int =
    when (this) {
        TrafficRange.DAY -> R.string.traffic_chart_range_hours
        TrafficRange.WEEK -> R.string.traffic_chart_range_days
        TrafficRange.MONTH -> R.string.traffic_chart_range_months
        TrafficRange.YEAR -> R.string.traffic_chart_range_years
    }
