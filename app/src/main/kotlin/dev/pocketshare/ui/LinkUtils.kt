package dev.pocketshare.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import dev.pocketshare.R

fun copyLink(
    context: Context,
    link: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), link))
}

fun shareLink(
    context: Context,
    link: String,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.action_share_link)),
    )
}
