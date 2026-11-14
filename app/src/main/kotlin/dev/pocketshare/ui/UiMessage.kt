package dev.pocketshare.ui

import androidx.annotation.StringRes

enum class UiTone {
    SUCCESS,
    INFO,
    ERROR,
}

data class UiMessage(
    @StringRes val textRes: Int,
    val tone: UiTone = UiTone.SUCCESS,
    @StringRes val bodyRes: Int? = null,
)
