package dev.pocketshare.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pocketshare.core.model.ShareExpiry
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.ui.theme.PocketShareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateShareDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun invalidDownloadLimitBlocksSubmission() {
        var confirmed = false
        composeRule.setContent {
            PocketShareTheme {
                CreateShareDialog(
                    kind = ShareKind.FILES,
                    selectedUris = listOf("content://test/a.jpg", "content://test/b.jpg"),
                    defaultExpiry = ShareExpiry.HOURS_24,
                    creating = false,
                    onAddFiles = {},
                    onRemoveFile = {},
                    onDismiss = {},
                    onConfirm = { _, _, _, _ -> confirmed = true },
                )
            }
        }

        composeRule.onNodeWithTag("share_limit").performTextInput("abc")
        composeRule.onNodeWithTag("share_create").performClick()
        composeRule.waitForIdle()

        assertFalse(confirmed)
    }

    @Test
    fun confirmReturnsEnteredOptions() {
        var name: String? = null
        var expiry: ShareExpiry? = null
        var password: String? = null
        var maxDownloads: Int? = null
        var confirmed = false

        composeRule.setContent {
            PocketShareTheme {
                CreateShareDialog(
                    kind = ShareKind.FOLDER,
                    selectedUris = listOf("content://test/folder"),
                    defaultExpiry = ShareExpiry.DAYS_7,
                    creating = false,
                    onAddFiles = {},
                    onRemoveFile = {},
                    onDismiss = {},
                    onConfirm = { enteredName, enteredExpiry, enteredPassword, enteredLimit ->
                        name = enteredName
                        expiry = enteredExpiry
                        password = enteredPassword
                        maxDownloads = enteredLimit
                        confirmed = true
                    },
                )
            }
        }

        composeRule.onNodeWithTag("share_name").performTextInput("Reports")
        composeRule.onNodeWithTag("share_password").performTextInput("secret")
        composeRule.onNodeWithTag("share_limit").performTextInput("3")
        composeRule.onNodeWithTag("share_create").performClick()
        composeRule.waitForIdle()

        assertTrue(confirmed)
        assertEquals("Reports", name)
        assertEquals(ShareExpiry.DAYS_7, expiry)
        assertEquals("secret", password)
        assertEquals(3, maxDownloads)
    }
}
