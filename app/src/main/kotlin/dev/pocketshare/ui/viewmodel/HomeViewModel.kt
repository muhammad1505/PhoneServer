package dev.pocketshare.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketshare.core.model.ShareState
import dev.pocketshare.core.model.ShareSummary
import dev.pocketshare.data.repository.ShareRepository
import dev.pocketshare.data.settings.AppSettings
import dev.pocketshare.data.settings.SettingsRepository
import dev.pocketshare.data.traffic.TrafficBucket
import dev.pocketshare.data.traffic.TrafficHistoryRepository
import dev.pocketshare.runtime.NetworkStatus
import dev.pocketshare.runtime.ShareRuntime
import dev.pocketshare.runtime.SharingState
import dev.pocketshare.runtime.TrafficMonitor
import dev.pocketshare.runtime.TrafficUsage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val runtime: ShareRuntime,
    shareRepository: ShareRepository,
    settingsRepository: SettingsRepository,
    trafficMonitor: TrafficMonitor,
    private val trafficRepository: TrafficHistoryRepository,
) : ViewModel() {
    val state: StateFlow<SharingState> = runtime.state
    val network: StateFlow<NetworkStatus> = runtime.network
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettings(),
        )

    /** Bytes moved by this app since the current sharing session started. */
    val traffic: StateFlow<TrafficUsage> = trafficMonitor.session

    val activeShares: StateFlow<List<ShareSummary>> =
        combine(
            shareRepository.shares,
            ticker(),
        ) { shares, now ->
            shares.filter { it.state(now) == ShareState.ACTIVE }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Persisted hourly buckets from [sinceEpochMs]. The dialog asks for just the window of the
     * range it is showing, so opening the history does not replay five years of samples.
     */
    fun trafficHistory(sinceEpochMs: Long): Flow<List<TrafficBucket>> = trafficRepository.observeSince(sinceEpochMs)

    fun publicLink(token: String): String? = runtime.publicLink(token)

    private fun ticker(): Flow<Long> =
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(30_000L)
            }
        }
}
