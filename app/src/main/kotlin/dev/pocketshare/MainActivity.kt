package dev.pocketshare

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import dev.pocketshare.ui.AppRoot
import dev.pocketshare.ui.components.SplashScreen
import dev.pocketshare.ui.theme.PocketShareTheme

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Leave the branded launch window behind before the first content frame.
        setTheme(R.style.Theme_PocketShare)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PocketShareTheme {
                // The intro only runs on a cold start, never on configuration changes.
                var showIntro by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                Crossfade(
                    targetState = showIntro,
                    animationSpec = tween(durationMillis = 260),
                    label = "splash-to-app",
                ) { introVisible ->
                    if (introVisible) {
                        SplashScreen(onReady = { showIntro = false })
                    } else {
                        AppRoot()
                    }
                }
            }
        }
    }
}
