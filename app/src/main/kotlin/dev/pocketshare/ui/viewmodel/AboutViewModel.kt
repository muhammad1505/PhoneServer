package dev.pocketshare.ui.viewmodel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketshare.ui.changelog.ChangelogRelease
import dev.pocketshare.ui.changelog.changelogVersion
import dev.pocketshare.ui.changelog.loadChangelog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class AboutInfo(
    val versionName: String = "-",
    val versionCode: Long = 0,
    val packageName: String = "-",
    val lastUpdated: String = "-",
    val debuggable: Boolean = false,
)

data class AboutState(
    val info: AboutInfo = AboutInfo(),
    val releases: List<ChangelogRelease> = emptyList(),
    val currentVersion: String = "",
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AboutState())
    val state: StateFlow<AboutState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { loadState() }
            mutableState.value = state
        }
    }

    private fun loadState(): AboutState {
        val info = readInfo()
        return AboutState(
            info = info,
            releases = loadChangelog(context),
            currentVersion = changelogVersion(info.versionName),
        )
    }

    private fun readInfo(): AboutInfo {
        val packageInfo: PackageInfo? =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        val appInfo = context.applicationInfo
        return AboutInfo(
            versionName = packageInfo?.versionName ?: "-",
            versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0,
            packageName = context.packageName,
            lastUpdated =
                packageInfo
                    ?.lastUpdateTime
                    ?.takeIf { it > 0 }
                    ?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
                    ?: "-",
            debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
    }
}
