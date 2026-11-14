package dev.pocketshare.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import dev.pocketshare.R
import dev.pocketshare.core.http.FileNames
import dev.pocketshare.core.model.ShareExpiry
import dev.pocketshare.core.model.ShareKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks for the remaining share options. File selection happens through SAF before this dialog is
 * shown, so the dialog only deals with non-secret metadata.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateShareDialog(
    kind: ShareKind,
    selectedUris: List<String>,
    defaultExpiry: ShareExpiry,
    creating: Boolean,
    onAddFiles: (List<Uri>) -> Unit,
    onRemoveFile: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        expiry: ShareExpiry,
        password: String?,
        maxDownloads: Int?,
    ) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var maxDownloadsText by rememberSaveable { mutableStateOf("") }
    var expiryName by rememberSaveable { mutableStateOf(defaultExpiry.name) }
    var showLimitError by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var resolvedNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val filePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                onAddFiles(uris)
            }
        }

    LaunchedEffect(selectedUris, kind) {
        val missing = selectedUris.filterNot { it in resolvedNames }
        if (missing.isNotEmpty()) {
            val names =
                withContext(Dispatchers.IO) {
                    missing.associateWith { raw ->
                        val document =
                            runCatching {
                                val uri = Uri.parse(raw)
                                if (kind == ShareKind.FOLDER) {
                                    DocumentFile.fromTreeUri(context, uri)
                                } else {
                                    DocumentFile.fromSingleUri(context, uri)
                                }
                            }.getOrNull()
                        FileNames.displayName(
                            document?.name,
                            if (kind == ShareKind.FOLDER) "folder" else "file",
                        )
                    }
                }
            resolvedNames = resolvedNames + names
        }
    }

    val expiry = runCatching { ShareExpiry.valueOf(expiryName) }.getOrDefault(defaultExpiry)
    val parsedLimit = maxDownloadsText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val fileList = selectedUris.map { uri -> uri to (resolvedNames[uri] ?: "…") }

    Dialog(
        onDismissRequest = { if (!creating) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(size = 44.dp, cornerRadius = 15.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text =
                                    stringResource(
                                        if (kind == ShareKind.FOLDER) {
                                            R.string.create_folder_title
                                        } else {
                                            R.string.create_files_title
                                        },
                                    ),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text =
                                    if (kind == ShareKind.FOLDER) {
                                        stringResource(R.string.selected_folder)
                                    } else {
                                        pluralStringResource(
                                            R.plurals.selected_files_count,
                                            selectedUris.size,
                                            selectedUris.size,
                                        )
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (kind == ShareKind.FILES) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            fileList.forEach { (uri, fileName) ->
                                SelectedFileRow(
                                    name = fileName,
                                    enabled = !creating,
                                    onRemove = { onRemoveFile(uri) },
                                )
                            }
                            OutlinedButton(
                                onClick = { filePicker.launch(arrayOf("*/*")) },
                                enabled = !creating,
                                shape = MaterialTheme.shapes.medium,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.action_add_files),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            if (selectedUris.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.create_need_files),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    } else {
                        val folderName = fileList.firstOrNull()?.second
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = folderName ?: stringResource(R.string.selected_folder),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.field_name)) },
                        placeholder = { Text(stringResource(R.string.field_name_hint)) },
                        singleLine = true,
                        enabled = !creating,
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("share_name"),
                    )

                    Text(
                        text = stringResource(R.string.field_expiry),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ShareExpiry.entries.forEach { option ->
                            FilterChip(
                                selected = expiry == option,
                                onClick = { expiryName = option.name },
                                label = { Text(stringResource(expiryLabel(option))) },
                                enabled = !creating,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.field_password)) },
                        placeholder = { Text(stringResource(R.string.field_password_hint)) },
                        singleLine = true,
                        enabled = !creating,
                        shape = MaterialTheme.shapes.medium,
                        visualTransformation =
                            if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector =
                                        if (passwordVisible) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                    contentDescription =
                                        stringResource(
                                            if (passwordVisible) R.string.hide_password else R.string.show_password,
                                        ),
                                )
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("share_password"),
                    )

                    OutlinedTextField(
                        value = maxDownloadsText,
                        onValueChange = {
                            maxDownloadsText = it
                            showLimitError = false
                        },
                        label = { Text(stringResource(R.string.field_max_downloads)) },
                        placeholder = { Text(stringResource(R.string.field_max_downloads_hint)) },
                        singleLine = true,
                        enabled = !creating,
                        shape = MaterialTheme.shapes.medium,
                        isError = showLimitError,
                        supportingText =
                            if (showLimitError) {
                                { Text(stringResource(R.string.field_max_downloads_invalid)) }
                            } else {
                                null
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("share_limit"),
                    )

                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.create_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !creating) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val invalidLimit =
                                    maxDownloadsText.isNotBlank() &&
                                        (parsedLimit == null || parsedLimit <= 0)
                                if (invalidLimit) {
                                    showLimitError = true
                                } else {
                                    onConfirm(
                                        name.trim(),
                                        expiry,
                                        password.takeIf { it.isNotBlank() },
                                        parsedLimit,
                                    )
                                }
                            },
                            enabled = !creating && (kind == ShareKind.FOLDER || selectedUris.isNotEmpty()),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.testTag("share_create"),
                        ) {
                            if (creating) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier
                                            .padding(end = 8.dp)
                                            .size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(stringResource(R.string.action_create))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedFileRow(
    name: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .padding(start = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.action_remove_file, name),
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
