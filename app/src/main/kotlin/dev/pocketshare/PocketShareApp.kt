package dev.pocketshare

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.HiltAndroidApp
import dev.pocketshare.core.model.AppLanguage
import dev.pocketshare.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PocketShareApp : Application() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            settingsRepository.settings
                .map { it.language }
                .distinctUntilChanged()
                .collect(::applyLanguage)
        }
    }

    private fun applyLanguage(language: AppLanguage) {
        val desired =
            when (language) {
                AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
                AppLanguage.INDONESIAN -> LocaleListCompat.forLanguageTags("in")
                AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
            }
        if (AppCompatDelegate.getApplicationLocales() != desired) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }
}
