package dev.pocketshare.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketshare.data.settings.SettingsRepository
import dev.pocketshare.ui.changelog.ChangelogRelease
import dev.pocketshare.ui.changelog.changelogVersion
import dev.pocketshare.ui.changelog.loadChangelog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val mutableRelease = MutableStateFlow<ChangelogRelease?>(null)
    val release: StateFlow<ChangelogRelease?> = mutableRelease.asStateFlow()

    init {
        viewModelScope.launch {
            val candidate = withContext(Dispatchers.IO) { resolveCandidate() }
            if (candidate != null) mutableRelease.value = candidate
        }
    }

    /**
     * Shows the changelog once per version after an update. A fresh install records the current
     * version silently so new users are not greeted by a release note for an app they just met.
     */
    private suspend fun resolveCandidate(): ChangelogRelease? {
        val info =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull() ?: return null
        val current = changelogVersion(info.versionName)
        if (current.isBlank()) return null

        val seen = settingsRepository.changelogSeenVersion()
        if (seen == current) return null

        val updated = info.lastUpdateTime > info.firstInstallTime + 1_000L
        if (seen == null && !updated) {
            settingsRepository.setChangelogSeenVersion(current)
            return null
        }

        val release = loadChangelog(context).firstOrNull { it.version == current }
        settingsRepository.setChangelogSeenVersion(current)
        return release
    }

    fun dismiss() {
        mutableRelease.value = null
    }
}
