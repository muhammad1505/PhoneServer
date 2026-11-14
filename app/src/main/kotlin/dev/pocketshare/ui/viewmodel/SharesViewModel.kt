package dev.pocketshare.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketshare.R
import dev.pocketshare.core.model.ShareExpiry
import dev.pocketshare.core.model.ShareFileRef
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.core.model.ShareSummary
import dev.pocketshare.data.repository.CreateShareOptions
import dev.pocketshare.data.repository.CreateShareResult
import dev.pocketshare.data.repository.ImportFailure
import dev.pocketshare.data.repository.PasswordChange
import dev.pocketshare.data.repository.ShareRepository
import dev.pocketshare.data.repository.UpdateShareOptions
import dev.pocketshare.data.settings.AppSettings
import dev.pocketshare.data.settings.SettingsRepository
import dev.pocketshare.runtime.ShareRuntime
import dev.pocketshare.ui.UiMessage
import dev.pocketshare.ui.UiTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SharesViewModel @Inject constructor(
    private val repository: ShareRepository,
    private val runtime: ShareRuntime,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages = mutableMessages.asSharedFlow()

    private val mutableCreating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = mutableCreating.asStateFlow()

    private val mutableSaving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = mutableSaving.asStateFlow()

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettings(),
        )

    val now: StateFlow<Long> =
        ticker().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            System.currentTimeMillis(),
        )

    val shares: StateFlow<List<ShareSummary>> =
        combine(repository.shares, now) { shares, _ ->
            shares
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createShare(
        uris: List<Uri>,
        kind: ShareKind,
        name: String,
        expiry: ShareExpiry,
        password: String?,
        maxDownloads: Int?,
    ) {
        if (mutableCreating.value) return
        mutableCreating.value = true
        viewModelScope.launch {
            val result =
                repository.createShare(
                    uris = uris,
                    options =
                        CreateShareOptions(
                            kind = kind,
                            displayName = name,
                            expiry = expiry,
                            password = password?.takeIf { it.isNotBlank() },
                            maxDownloads = maxDownloads,
                        ),
                )
            mutableCreating.value = false
            when (result) {
                is CreateShareResult.Success ->
                    emitMessage(
                        textRes = R.string.share_created,
                        bodyRes = R.string.settings_restart_required,
                    )

                is CreateShareResult.Failure ->
                    emitMessage(
                        textRes = failureMessage(result.reason),
                        tone = UiTone.ERROR,
                    )
            }
        }
    }

    fun revoke(shareId: Long) {
        viewModelScope.launch {
            repository.revoke(shareId)
            emitMessage(R.string.share_revoked)
        }
    }

    fun delete(shareId: Long) {
        viewModelScope.launch {
            repository.delete(shareId)
            emitMessage(R.string.share_deleted)
        }
    }

    fun updateExpiry(
        shareId: Long,
        expiry: ShareExpiry,
    ) {
        viewModelScope.launch {
            repository.updateExpiry(shareId, expiry)
            emitMessage(R.string.share_expiry_updated)
        }
    }

    fun updateDetails(
        shareId: Long,
        displayName: String,
        expiry: ShareExpiry?,
        passwordChange: PasswordChange,
        maxDownloads: Int?,
        files: List<ShareFileRef>? = null,
    ) {
        if (mutableSaving.value) return
        mutableSaving.value = true
        viewModelScope.launch {
            val updated =
                repository.updateDetails(
                    shareId = shareId,
                    options =
                        UpdateShareOptions(
                            displayName = displayName,
                            expiry = expiry,
                            passwordChange = passwordChange,
                            maxDownloads = maxDownloads,
                            files = files,
                        ),
                )
            mutableSaving.value = false
            if (updated) {
                emitMessage(R.string.share_updated)
            } else {
                emitMessage(R.string.share_update_failed, tone = UiTone.ERROR)
            }
        }
    }

    fun publicLink(token: String): String? = runtime.publicLink(token)

    private fun emitMessage(
        textRes: Int,
        tone: UiTone = UiTone.SUCCESS,
        bodyRes: Int? = null,
    ) {
        mutableMessages.tryEmit(UiMessage(textRes = textRes, tone = tone, bodyRes = bodyRes))
    }

    private fun failureMessage(reason: ImportFailure): Int =
        when (reason) {
            ImportFailure.NO_FILES -> R.string.error_no_files
            ImportFailure.FOLDER_UNREADABLE -> R.string.error_folder_unreadable
            ImportFailure.FILE_UNREADABLE -> R.string.error_file_unreadable
            ImportFailure.PERMISSION_NOT_PERSISTED -> R.string.error_permission
            ImportFailure.STORAGE_ERROR -> R.string.error_storage
        }

    private fun ticker(): Flow<Long> =
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(30_000L)
            }
        }
}
