package dev.pocketshare.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketshare.R
import dev.pocketshare.core.http.FileNames
import dev.pocketshare.core.model.ShareExpiry
import dev.pocketshare.core.model.ShareFileRef
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.core.model.ShareState
import dev.pocketshare.core.model.ShareSummary
import dev.pocketshare.data.repository.PasswordChange
import dev.pocketshare.ui.UiMessage
import dev.pocketshare.ui.UiTone
import dev.pocketshare.ui.components.ConfirmDialog
import dev.pocketshare.ui.components.EmptyState
import dev.pocketshare.ui.components.IconTile
import dev.pocketshare.ui.components.LocalUiMessageHost
import dev.pocketshare.ui.components.QrDialog
import dev.pocketshare.ui.components.ScreenHeader
import dev.pocketshare.ui.components.expiryLabel
import dev.pocketshare.ui.components.formatDateTime
import dev.pocketshare.ui.components.shareStateLabel
import dev.pocketshare.ui.copyLink
import dev.pocketshare.ui.shareLink
import dev.pocketshare.ui.theme.LocalBrandColors
import dev.pocketshare.ui.viewmodel.SharesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ShareFilter(
    @param:StringRes val label: Int,
) {
    ALL(R.string.shares_filter_all),
    ACTIVE(R.string.shares_filter_active),
    ENDED(R.string.shares_filter_ended),
}

@Composable
fun SharesScreen(
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    viewModel: SharesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val showMessage = LocalUiMessageHost.current
    val shares by viewModel.shares.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    var filterName by rememberSaveable { mutableStateOf(ShareFilter.ALL.name) }
    val filter = runCatching { ShareFilter.valueOf(filterName) }.getOrDefault(ShareFilter.ALL)
    var newShareMenu by remember { mutableStateOf(false) }
    var qrLink by remember { mutableStateOf<String?>(null) }
    var revokeCandidate by remember { mutableStateOf<ShareSummary?>(null) }
    var deleteCandidate by remember { mutableStateOf<ShareSummary?>(null) }
    var editCandidate by remember { mutableStateOf<ShareSummary?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect(showMessage)
    }

    fun showMissingLink() {
        showMessage(
            UiMessage(
                R.string.share_link_needs_server,
                UiTone.INFO,
                R.string.share_link_needs_server_body,
            ),
        )
    }

    fun copy(share: ShareSummary) {
        val link = viewModel.publicLink(share.token)
        if (link == null) {
            showMessage(UiMessage(R.string.share_copy_unavailable, UiTone.INFO))
            return
        }
        copyLink(context, link)
        showMessage(
            UiMessage(
                textRes = R.string.link_copied,
                tone = UiTone.SUCCESS,
                bodyRes = R.string.link_copied_body,
            ),
        )
    }

    fun sendLink(share: ShareSummary) {
        val link = viewModel.publicLink(share.token)
        if (link == null) showMissingLink() else shareLink(context, link)
    }

    fun openQr(share: ShareSummary) {
        val link = viewModel.publicLink(share.token)
        if (link == null) showMissingLink() else qrLink = link
    }

    val activeCount = shares.count { it.state(now) == ShareState.ACTIVE }
    val endedCount = shares.size - activeCount
    val filtered =
        when (filter) {
            ShareFilter.ALL -> shares
            ShareFilter.ACTIVE -> shares.filter { it.state(now) == ShareState.ACTIVE }
            ShareFilter.ENDED -> shares.filter { it.state(now) != ShareState.ACTIVE }
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.shares_title),
                subtitle = stringResource(R.string.shares_subtitle),
                action = {
                    Box {
                        FilledTonalButton(
                            onClick = { newShareMenu = true },
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.action_new_share),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        DropdownMenu(
                            expanded = newShareMenu,
                            onDismissRequest = { newShareMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_new_files)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Description, contentDescription = null)
                                },
                                onClick = {
                                    newShareMenu = false
                                    onPickFiles()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_new_folder)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Folder, contentDescription = null)
                                },
                                onClick = {
                                    newShareMenu = false
                                    onPickFolder()
                                },
                            )
                        }
                    }
                },
            )
        }

        if (shares.isNotEmpty()) {
            item {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ShareFilter.entries.forEach { option ->
                        val count =
                            when (option) {
                                ShareFilter.ALL -> shares.size
                                ShareFilter.ACTIVE -> activeCount
                                ShareFilter.ENDED -> endedCount
                            }
                        FilterChip(
                            selected = filter == option,
                            onClick = { filterName = option.name },
                            label = {
                                Text(
                                    text = "${stringResource(option.label)} · $count",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
            }
        }

        when {
            shares.isEmpty() ->
                item {
                    EmptyState(
                        icon = Icons.Outlined.FolderOpen,
                        title = R.string.shares_empty_title,
                        body = R.string.shares_empty_body,
                    )
                }

            filtered.isEmpty() ->
                item {
                    EmptyState(
                        icon = Icons.Outlined.FilterList,
                        title = R.string.shares_filter_empty_title,
                        body = R.string.shares_filter_empty_body,
                    )
                }

            else ->
                items(filtered, key = { it.id }) { share ->
                    ShareRow(
                        share = share,
                        now = now,
                        onCopy = { copy(share) },
                        onShare = { sendLink(share) },
                        onShowQr = { openQr(share) },
                        onEdit = { editCandidate = share },
                        onRevoke = { revokeCandidate = share },
                        onDelete = { deleteCandidate = share },
                    )
                }
        }
    }

    qrLink?.let { link ->
        QrDialog(link = link, onDismiss = { qrLink = null })
    }

    revokeCandidate?.let { share ->
        ConfirmDialog(
            title = stringResource(R.string.share_revoke_confirm_title),
            body = share.displayName + "\n\n" + stringResource(R.string.share_revoke_confirm_body),
            confirmLabel = stringResource(R.string.action_revoke),
            onConfirm = {
                viewModel.revoke(share.id)
                revokeCandidate = null
            },
            onDismiss = { revokeCandidate = null },
            icon = Icons.Outlined.LinkOff,
            destructive = true,
        )
    }

    deleteCandidate?.let { share ->
        ConfirmDialog(
            title = stringResource(R.string.share_delete_confirm_title),
            body = share.displayName + "\n\n" + stringResource(R.string.share_delete_confirm_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.delete(share.id)
                deleteCandidate = null
            },
            onDismiss = { deleteCandidate = null },
            icon = Icons.Outlined.Delete,
            destructive = true,
        )
    }

    editCandidate?.let { share ->
        EditShareDialog(
            share = share,
            onDismiss = { editCandidate = null },
            onConfirm = { displayName, expiry, passwordChange, maxDownloads, files ->
                viewModel.updateDetails(
                    shareId = share.id,
                    displayName = displayName,
                    expiry = expiry,
                    passwordChange = passwordChange,
                    maxDownloads = maxDownloads,
                    files = files,
                )
                editCandidate = null
            },
        )
    }
}

@Composable
private fun ShareRow(
    share: ShareSummary,
    now: Long,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onShowQr: () -> Unit,
    onEdit: () -> Unit,
    onRevoke: () -> Unit,
    onDelete: () -> Unit,
) {
    val brand = LocalBrandColors.current
    val state = share.state(now)
    val active = state == ShareState.ACTIVE
    val editable = state != ShareState.REVOKED
    var menuOpen by remember { mutableStateOf(false) }
    val statusColor =
        when (state) {
            ShareState.ACTIVE -> brand.success
            ShareState.EXPIRED,
            ShareState.EXHAUSTED,
            -> brand.warning

            ShareState.REVOKED -> MaterialTheme.colorScheme.error
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(enabled = editable, onClick = onEdit),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 11.dp, top = 9.dp, end = 2.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = if (share.kind == ShareKind.FOLDER) Icons.Outlined.Folder else Icons.Outlined.Description,
                size = 38.dp,
                containerColor =
                    if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                contentColor =
                    if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = share.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(shareStateLabel(state)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text =
                            if (share.kind == ShareKind.FOLDER) {
                                stringResource(R.string.share_kind_folder)
                            } else {
                                pluralStringResource(
                                    R.plurals.share_file_count,
                                    share.fileCount,
                                    share.fileCount,
                                )
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .padding(start = 6.dp)
                                .size(11.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text =
                            share.maxDownloads?.let { max ->
                                stringResource(R.string.share_downloads_count_short, share.downloadCount, max)
                            } ?: stringResource(R.string.share_downloads_short, share.downloadCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (share.hasPassword) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = stringResource(R.string.share_password_protected),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .padding(start = 4.dp)
                                    .size(11.dp),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text =
                            share.expiresAtEpochMs?.let { expiresAt ->
                                stringResource(R.string.share_expires_on, formatDateTime(expiresAt))
                            } ?: stringResource(R.string.share_no_expiry),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (active) {
                IconButton(onClick = onCopy, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy_link),
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onShowQr, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.QrCode2,
                        contentDescription = stringResource(R.string.action_show_qr),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    if (editable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                    }
                    if (active) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_share_link)) },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onShare()
                            },
                        )
                    }
                    if (editable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_revoke)) },
                            leadingIcon = { Icon(Icons.Outlined.LinkOff, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRevoke()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditShareDialog(
    share: ShareSummary,
    onDismiss: () -> Unit,
    onConfirm: (
        displayName: String,
        expiry: ShareExpiry?,
        passwordChange: PasswordChange,
        maxDownloads: Int?,
        files: List<ShareFileRef>?,
    ) -> Unit,
) {
    var name by rememberSaveable(share.id) { mutableStateOf(share.displayName) }
    var expiryName by rememberSaveable(share.id) { mutableStateOf(EXPIRY_KEEP) }
    var passwordMode by rememberSaveable(share.id) { mutableStateOf(PASSWORD_KEEP) }
    var password by rememberSaveable(share.id) { mutableStateOf("") }
    var passwordVisible by rememberSaveable(share.id) { mutableStateOf(false) }
    var maxDownloadsText by rememberSaveable(share.id) {
        mutableStateOf(share.maxDownloads?.toString().orEmpty())
    }
    var showLimitError by rememberSaveable(share.id) { mutableStateOf(false) }
    var showPasswordError by rememberSaveable(share.id) { mutableStateOf(false) }
    var draftFiles by remember(share.id) { mutableStateOf(share.files) }
    var pendingFiles by remember(share.id) { mutableStateOf<List<Uri>?>(null) }
    var resolvingFiles by remember(share.id) { mutableStateOf(false) }
    val context = LocalContext.current

    val filePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) pendingFiles = uris
        }

    LaunchedEffect(pendingFiles) {
        val uris = pendingFiles ?: return@LaunchedEffect
        resolvingFiles = true
        val refs =
            withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val document = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
                    ShareFileRef(
                        uri = uri.toString(),
                        name = FileNames.displayName(document?.name, "file"),
                    )
                }
            }
        draftFiles = (draftFiles + refs).distinctBy { it.uri }
        resolvingFiles = false
        pendingFiles = null
    }

    val expiry = if (expiryName == EXPIRY_KEEP) null else runCatching { ShareExpiry.valueOf(expiryName) }.getOrNull()
    val parsedLimit = maxDownloadsText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val fileShare = share.kind == ShareKind.FILES

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 18.dp, vertical = 20.dp),
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
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                tonalElevation = 5.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(
                            icon = Icons.Outlined.Edit,
                            size = 42.dp,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(11.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = stringResource(R.string.edit_share_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(R.string.edit_share_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.field_name)) },
                        placeholder = { Text(stringResource(R.string.field_name_hint)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (fileShare) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.edit_files_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = pluralStringResource(R.plurals.share_file_count, draftFiles.size, draftFiles.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            draftFiles.forEach { file ->
                                DraftFileRow(
                                    name = file.name,
                                    enabled = !resolvingFiles,
                                    onRemove = {
                                        draftFiles = draftFiles.filterNot { it.uri == file.uri }
                                    },
                                )
                            }
                            OutlinedButton(
                                onClick = { filePicker.launch(arrayOf("*/*")) },
                                enabled = !resolvingFiles,
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
                            Text(
                                text =
                                    if (draftFiles.isEmpty()) {
                                        stringResource(R.string.edit_files_need_one)
                                    } else {
                                        stringResource(R.string.edit_files_hint)
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                color =
                                    if (draftFiles.isEmpty()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.field_expiry),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        FilterChip(
                            selected = expiryName == EXPIRY_KEEP,
                            onClick = { expiryName = EXPIRY_KEEP },
                            label = { Text(stringResource(R.string.field_expiry_keep)) },
                        )
                        ShareExpiry.entries.forEach { option ->
                            FilterChip(
                                selected = expiry == option,
                                onClick = { expiryName = option.name },
                                label = { Text(stringResource(expiryLabel(option))) },
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.edit_password_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        FilterChip(
                            selected = passwordMode == PASSWORD_KEEP,
                            onClick = {
                                passwordMode = PASSWORD_KEEP
                                showPasswordError = false
                            },
                            label = { Text(stringResource(R.string.edit_password_keep)) },
                        )
                        if (share.hasPassword) {
                            FilterChip(
                                selected = passwordMode == PASSWORD_CLEAR,
                                onClick = {
                                    passwordMode = PASSWORD_CLEAR
                                    showPasswordError = false
                                },
                                label = { Text(stringResource(R.string.edit_password_remove)) },
                            )
                        }
                        FilterChip(
                            selected = passwordMode == PASSWORD_SET,
                            onClick = { passwordMode = PASSWORD_SET },
                            label = { Text(stringResource(R.string.edit_password_set)) },
                        )
                    }
                    Text(
                        text =
                            when {
                                passwordMode == PASSWORD_SET -> stringResource(R.string.field_password_hint)
                                passwordMode == PASSWORD_KEEP && share.hasPassword ->
                                    stringResource(R.string.edit_password_current)

                                else -> stringResource(R.string.edit_password_none)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (showPasswordError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    if (passwordMode == PASSWORD_SET) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                showPasswordError = false
                            },
                            label = { Text(stringResource(R.string.field_password)) },
                            placeholder = { Text(stringResource(R.string.field_password_hint)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            isError = showPasswordError,
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    OutlinedTextField(
                        value = maxDownloadsText,
                        onValueChange = {
                            maxDownloadsText = it
                            showLimitError = false
                        },
                        label = { Text(stringResource(R.string.field_max_downloads)) },
                        placeholder = { Text(stringResource(R.string.field_max_downloads_hint)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        isError = showLimitError,
                        supportingText = {
                            Text(
                                text =
                                    if (showLimitError) {
                                        stringResource(R.string.edit_downloads_invalid, share.downloadCount)
                                    } else if (share.downloadCount > 0) {
                                        stringResource(R.string.edit_downloads_hint, share.downloadCount)
                                    } else {
                                        stringResource(R.string.field_max_downloads_hint)
                                    },
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val invalidLimit =
                                    maxDownloadsText.isNotBlank() &&
                                        (
                                            parsedLimit == null ||
                                                parsedLimit <= 0 ||
                                                parsedLimit <= share.downloadCount
                                        )
                                val invalidPassword = passwordMode == PASSWORD_SET && password.isBlank()
                                showLimitError = invalidLimit
                                showPasswordError = invalidPassword
                                if (!invalidLimit && !invalidPassword && !resolvingFiles) {
                                    onConfirm(
                                        name.trim(),
                                        expiry,
                                        when (passwordMode) {
                                            PASSWORD_CLEAR -> PasswordChange.Clear
                                            PASSWORD_SET -> PasswordChange.Set(password)
                                            else -> PasswordChange.Keep
                                        },
                                        parsedLimit,
                                        if (fileShare) draftFiles else null,
                                    )
                                }
                            },
                            enabled = !resolvingFiles && (!fileShare || draftFiles.isNotEmpty()),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(stringResource(R.string.action_save))
                        }
                    }

                    Text(
                        text = stringResource(R.string.create_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private const val EXPIRY_KEEP = "keep"
private const val PASSWORD_KEEP = "keep"
private const val PASSWORD_CLEAR = "clear"
private const val PASSWORD_SET = "set"

@Composable
private fun DraftFileRow(
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
