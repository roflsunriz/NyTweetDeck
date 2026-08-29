package dev.nytweetdeck.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.AccountAuthStatus
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.ListOption
import dev.nytweetdeck.android.model.MainMenuItemId
import dev.nytweetdeck.android.model.TargetPickerState
import dev.nytweetdeck.android.model.TimelineLoadStatus

internal enum class TransferStatus {
    NONE,
    EXPORT_SUCCESS,
    IMPORT_SUCCESS,
    FAILED,
}

@Composable
internal fun AddColumnDialog(
    pickerState: TargetPickerState,
    onDismiss: () -> Unit,
    onAdd: (ColumnKind, String, String?) -> Unit,
    onResolveUser: (String) -> Unit,
    onLoadLists: (String?) -> Unit,
    onSelectList: (ListOption) -> Unit,
) {
    var pendingKind by rememberSaveable { mutableStateOf<ColumnKind?>(null) }
    var targetInput by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(pickerState.completedColumnId) {
        if (pickerState.completedColumnId != null) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_column)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                columnChoices().forEach { (kind, labelRes) ->
                    val title = stringResource(labelRes)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (kind.requiresTarget()) {
                                    pendingKind = kind
                                    targetInput = ""
                                    if (kind == ColumnKind.LIST) onLoadLists(null)
                                } else {
                                    onAdd(kind, title, null)
                                }
                            }
                            .padding(vertical = 12.dp)
                            .testTag("add-${kind.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(iconFor(kind), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(title)
                    }
                }
                pendingKind?.let { kind ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    val label = when (kind) {
                        ColumnKind.SEARCH -> stringResource(R.string.search_query)
                        ColumnKind.USER -> stringResource(R.string.x_username)
                        ColumnKind.LIST -> stringResource(R.string.list_search_query)
                        else -> ""
                    }
                    OutlinedTextField(
                        value = targetInput,
                        onValueChange = { targetInput = it.take(200) },
                        modifier = Modifier.fillMaxWidth().testTag("column-target"),
                        label = { Text(label) },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            when (kind) {
                                ColumnKind.SEARCH -> onAdd(kind, targetInput.trim(), targetInput.trim())
                                ColumnKind.USER -> onResolveUser(targetInput)
                                ColumnKind.LIST -> onLoadLists(targetInput)
                                else -> Unit
                            }
                        },
                        enabled = when (kind) {
                            ColumnKind.SEARCH -> targetInput.isNotBlank()
                            ColumnKind.USER -> targetInput.trim().removePrefix("@").matches(
                                Regex("[A-Za-z0-9_]{1,15}"),
                            )
                            ColumnKind.LIST -> targetInput.isBlank() || targetInput.length <= 100
                            else -> false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("confirm-target-column"),
                    ) { Text(stringResource(R.string.add_column)) }
                    when (pickerState.status) {
                        TimelineLoadStatus.LOADING -> CircularProgressIndicator(
                            Modifier.size(28.dp).testTag("target-picker-loading"),
                        )
                        TimelineLoadStatus.FAILED -> Text(
                            stringResource(R.string.target_lookup_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("target-picker-failed"),
                        )
                        TimelineLoadStatus.READY -> if (kind == ColumnKind.LIST) {
                            pickerState.listOptions.take(50).forEach { option ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectList(option) }
                                        .padding(vertical = 10.dp)
                                        .testTag("list-option-${option.id}"),
                                ) {
                                    Text(option.name, fontWeight = FontWeight.SemiBold)
                                    option.ownerUsername?.let {
                                        Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    option.description?.let {
                                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        TimelineLoadStatus.IDLE -> Unit
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-add-column")) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
internal fun MenuEditorDialog(
    selected: List<MainMenuItemId>,
    onToggle: (MainMenuItemId) -> Unit,
    onMove: (MainMenuItemId, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_menu)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MainMenuItemId.entries.forEach { id ->
                    val definition = menuDefinition(id)
                    val isSelected = id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isSelected) {
                                Modifier.pointerInput(id) {
                                    var accumulatedY = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragEnd = { accumulatedY = 0f },
                                        onDragCancel = { accumulatedY = 0f },
                                    ) { change, drag ->
                                        accumulatedY += drag.y
                                        if (kotlin.math.abs(accumulatedY) >= 40.dp.toPx()) {
                                            onMove(id, if (accumulatedY > 0) 1 else -1)
                                            accumulatedY = 0f
                                        }
                                        change.consume()
                                    }
                                }
                            } else {
                                Modifier
                            })
                            .clickable { onToggle(id) }
                            .padding(vertical = 6.dp)
                            .testTag("menu-option-${id.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isSelected, onCheckedChange = { onToggle(id) })
                        Icon(definition.icon, null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(definition.labelRes), Modifier.weight(1f))
                        if (isSelected) {
                            IconButton(onClick = { onMove(id, -1) }) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up))
                            }
                            IconButton(onClick = { onMove(id, 1) }) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-menu-editor")) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
internal fun SimpleComposerDialog(onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compose_post)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(10_000) },
                modifier = Modifier.fillMaxWidth().imePadding().testTag("composer-text"),
                label = { Text(stringResource(R.string.post_text)) },
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-composer")) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
internal fun AccountsDialog(
    state: DeckUiState,
    onDismiss: () -> Unit,
    onLogin: () -> Unit,
    onSelectAccount: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accounts)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.accounts.forEach { account ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAccount(account.accountId) }
                            .padding(vertical = 8.dp)
                            .testTag("account-${account.accountId}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(account.displayName, fontWeight = FontWeight.SemiBold)
                            Text("@${account.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.selectedAccountId == account.accountId) {
                            Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                when (state.accountAuthStatus) {
                    AccountAuthStatus.VERIFYING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.verifying_x_account))
                        }
                    }
                    AccountAuthStatus.FAILED -> Text(
                        stringResource(R.string.x_account_verification_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    AccountAuthStatus.IDLE -> Unit
                }
                Button(
                    onClick = onLogin,
                    enabled = state.accountAuthStatus != AccountAuthStatus.VERIFYING,
                    modifier = Modifier.fillMaxWidth().testTag("login-x"),
                ) {
                    Text(stringResource(R.string.login_to_x))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-accounts")) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
internal fun SettingsDialog(
    state: DeckUiState,
    onDarkThemeChange: (Boolean) -> Unit,
    onCompactDensityChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    transferStatus: TransferStatus,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(modifier = Modifier.widthIn(min = 260.dp)) {
                SettingCheckbox(
                    label = stringResource(R.string.dark_theme),
                    checked = state.useDarkTheme,
                    onCheckedChange = onDarkThemeChange,
                    tag = "setting-dark-theme",
                )
                SettingCheckbox(
                    label = stringResource(R.string.compact_density),
                    checked = state.compactDensity,
                    onCheckedChange = onCompactDensityChange,
                    tag = "setting-compact-density",
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Button(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth().testTag("export-settings"),
                ) {
                    Text(stringResource(R.string.export_settings))
                }
                Button(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth().testTag("import-settings"),
                ) {
                    Text(stringResource(R.string.import_settings))
                }
                val statusText = when (transferStatus) {
                    TransferStatus.EXPORT_SUCCESS -> R.string.export_settings_success
                    TransferStatus.IMPORT_SUCCESS -> R.string.import_settings_success
                    TransferStatus.FAILED -> R.string.settings_transfer_failed
                    TransferStatus.NONE -> null
                }
                statusText?.let { resource ->
                    Text(
                        stringResource(resource),
                        color = if (transferStatus == TransferStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun SettingCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun iconFor(kind: ColumnKind): ImageVector = when (kind) {
    ColumnKind.HOME_FOR_YOU -> Icons.Default.Home
    ColumnKind.HOME_FOLLOWING -> Icons.Default.People
    ColumnKind.NOTIFICATIONS -> Icons.Default.Notifications
    ColumnKind.MESSAGES -> Icons.Default.MailOutline
    ColumnKind.TRENDS -> Icons.AutoMirrored.Filled.TrendingUp
    ColumnKind.SEARCH -> Icons.Default.Search
    ColumnKind.HISTORY -> Icons.Default.Home
    ColumnKind.USER -> Icons.Default.AccountCircle
    ColumnKind.LIST -> Icons.AutoMirrored.Filled.List
}

private fun columnChoices(): List<Pair<ColumnKind, Int>> = listOf(
    ColumnKind.HOME_FOR_YOU to R.string.for_you,
    ColumnKind.HOME_FOLLOWING to R.string.following,
    ColumnKind.NOTIFICATIONS to R.string.notifications,
    ColumnKind.MESSAGES to R.string.direct_messages,
    ColumnKind.TRENDS to R.string.trends,
    ColumnKind.SEARCH to R.string.search,
    ColumnKind.HISTORY to R.string.history,
    ColumnKind.USER to R.string.user_timeline,
    ColumnKind.LIST to R.string.list_timeline,
)

private fun ColumnKind.requiresTarget(): Boolean =
    this == ColumnKind.SEARCH || this == ColumnKind.USER || this == ColumnKind.LIST
