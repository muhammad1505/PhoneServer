package dev.pocketshare.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketshare.R
import dev.pocketshare.ui.theme.LocalBrandColors
import kotlinx.coroutines.delay

/**
 * Continues the launch window: the logo tile already sits in the middle of the screen, so only the
 * name, tagline and loading bar fade in before handing over to the app.
 */
@Composable
fun SplashScreen(
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var started by remember { mutableStateOf(false) }
    var targetProgress by remember { mutableFloatStateOf(0f) }
    val progress by
        animateFloatAsState(
            targetValue = targetProgress,
            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
            label = "splash-progress",
        )
    val captionAlpha by
        animateFloatAsState(
            targetValue = if (started) 1f else 0f,
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            label = "splash-caption",
        )
    val brand = LocalBrandColors.current
    val background = MaterialTheme.colorScheme.background
    val tinted = MaterialTheme.colorScheme.surfaceContainerLow

    LaunchedEffect(Unit) {
        started = true
        delay(70)
        targetProgress = 0.72f
        delay(330)
        targetProgress = 1f
        delay(160)
        onReady()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(background, tinted))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = captionAlpha },
        ) {
            val topLeft = Offset(size.width * 0.16f, size.height * 0.1f)
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(brand.glowA, Color.Transparent),
                        center = topLeft,
                        radius = size.width * 0.85f,
                    ),
                radius = size.width * 0.85f,
                center = topLeft,
            )
            val topRight = Offset(size.width * 0.92f, size.height * 0.06f)
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(brand.glowB, Color.Transparent),
                        center = topRight,
                        radius = size.width * 0.7f,
                    ),
                radius = size.width * 0.7f,
                center = topRight,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Keeps the tile exactly where the launch window draws it: screen center.
            modifier = Modifier.offset(y = 58.dp),
        ) {
            BrandTile()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier.graphicsLayer {
                        alpha = captionAlpha
                        translationY = (1f - captionAlpha) * 16f
                    },
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.6).sp),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 22.dp),
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier =
                        Modifier
                            .padding(top = 6.dp)
                            .padding(horizontal = 40.dp),
                )
                LoadingBar(progress = progress, modifier = Modifier.padding(top = 34.dp))
            }
        }
    }
}

@Composable
private fun BrandTile() {
    Box(
        modifier =
            Modifier
                .size(104.dp)
                .shadow(
                    elevation = 26.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = Color(0x33042A22),
                    spotColor = Color(0x55042A22),
                ).clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF127A65), Color(0xFF063B31))),
                ).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier.size(54.dp),
        )
    }
}

@Composable
private fun LoadingBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val loadingLabel = stringResource(R.string.splash_loading)
    Box(
        modifier =
            modifier
                .width(150.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics {
                    contentDescription = loadingLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.55f), accent),
                        ),
                    ),
        )
    }
}
