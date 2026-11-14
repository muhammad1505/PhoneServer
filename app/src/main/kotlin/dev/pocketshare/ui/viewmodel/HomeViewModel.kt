package dev.pocketshare.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketshare.core.model.ShareState
import dev.pocketshare.core.model.ShareSummary
import dev.pocketshare.data.repository.ShareRepository
import dev.pocketshare.data.settings.AppSettings
import dev.pocketshare.data.settings.SettingsRepository
import dev.pocketshare.runtime.NetworkStatus
import dev.pocketshare.runtime.ShareRuntime
import dev.pocketshare.runtime.SharingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val runtime: ShareRuntime,
    shareRepository: ShareRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<SharingState> = runtime.state
    val network: StateFlow<NetworkStatus> = runtime.network
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettings(),
        )

    private val _traffic = MutableStateFlow(TrafficUsage())

    /** Bytes moved by this app since the current sharing session started. */
    val traffic: StateFlow<TrafficUsage> = _traffic.asStateFlow()

    val activeShares: StateFlow<List<ShareSummary>> =
        combine(
            shareRepository.shares,
            ticker(),
        ) { shares, now ->
            shares.filter { it.state(now) == ShareState.ACTIVE }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            var baseline: TrafficMeter.Sample? = null
            while (true) {
                val sharing = runtime.state.value.isSharing
                if (!sharing) {
                    baseline = null
                    if (_traffic.value.available) _traffic.value = TrafficUsage()
                } else {
                    val sample = TrafficMeter.sample()
                    if (sample == null) {
                        if (_traffic.value.available) _traffic.value = TrafficUsage()
                    } else {
                        val start = baseline ?: sample.also { baseline = it }
                        _traffic.value =
                            TrafficUsage(
                                sentBytes = (sample.sent - start.sent).coerceAtLeast(0L),
                                receivedBytes = (sample.received - start.received).coerceAtLeast(0L),
                                available = true,
                            )
                    }
                }
                // Only poll while a session is up; idle screens should not wake the device.
                delay(if (sharing) 3_000L else 10_000L)
            }
        }
    }

    fun publicLink(token: String): String? = runtime.publicLink(token)

    private fun ticker(): Flow<Long> =
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(30_000L)
            }
        }
}
