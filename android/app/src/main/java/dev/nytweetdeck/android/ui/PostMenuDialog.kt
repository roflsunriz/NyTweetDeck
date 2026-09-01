package dev.nytweetdeck.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostMenuAction

@Composable
internal fun PostMenuDialog(
    post: Post,
    onAction: (Post, PostMenuAction) -> Unit,
    onListMembership: (Post, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmBlock by remember { mutableStateOf(false) }
    var showListMembership by remember { mutableStateOf(false) }
    var listId by remember { mutableStateOf("") }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(-12, 72),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 360.dp)
                .heightIn(max = 560.dp)
                .semantics { role = Role.DropdownList }
                .testTag("post-menu"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.post_menu_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-post-menu")) {
                        Text(stringResource(R.string.close))
                    }
                }
            when {
                confirmBlock -> BlockConfirmation(
                    onConfirm = { onAction(post, PostMenuAction.BLOCK) },
                    onCancel = { confirmBlock = false },
                )
                showListMembership -> ListMembershipForm(
                    listId = listId,
                    onListIdChange = { listId = it.filter(Char::isDigit).take(30) },
                    onSubmit = { add -> onListMembership(post, listId, add) },
                    onCancel = { showListMembership = false },
                )
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    menuActions().forEach { (action, label) ->
                        Text(
                            text = stringResource(label),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (action == PostMenuAction.BLOCK) confirmBlock = true
                                    else onAction(post, action)
                                }
                                .padding(vertical = 12.dp)
                                .testTag("post-menu-action-${action.name.lowercase()}"),
                        )
                    }
                    Text(
                        text = stringResource(R.string.post_menu_list_membership),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showListMembership = true }
                            .padding(vertical = 12.dp)
                            .testTag("post-menu-list-membership"),
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun BlockConfirmation(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.testTag("block-confirmation")) {
        Text(stringResource(R.string.post_menu_block_confirmation))
        Row {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onConfirm, modifier = Modifier.testTag("confirm-block")) {
                Text(stringResource(R.string.post_menu_block))
            }
        }
    }
}

@Composable
private fun ListMembershipForm(
    listId: String,
    onListIdChange: (String) -> Unit,
    onSubmit: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = listId,
            onValueChange = onListIdChange,
            label = { Text(stringResource(R.string.post_menu_list_id)) },
            modifier = Modifier.fillMaxWidth().testTag("post-menu-list-id"),
            singleLine = true,
        )
        Row {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = { onSubmit(true) },
                enabled = listId.matches(Regex("[0-9]{1,30}")),
                modifier = Modifier.testTag("post-menu-list-add"),
            ) { Text(stringResource(R.string.post_menu_list_add)) }
            TextButton(
                onClick = { onSubmit(false) },
                enabled = listId.matches(Regex("[0-9]{1,30}")),
                modifier = Modifier.testTag("post-menu-list-remove"),
            ) { Text(stringResource(R.string.post_menu_list_remove)) }
        }
    }
}

private fun menuActions() = listOf(
    PostMenuAction.NOT_INTERESTED to R.string.post_menu_not_interested,
    PostMenuAction.FOLLOW to R.string.post_menu_follow,
    PostMenuAction.MUTE to R.string.post_menu_mute,
    PostMenuAction.BLOCK to R.string.post_menu_block,
    PostMenuAction.ACTIVITY to R.string.post_menu_activity,
    PostMenuAction.EMBED to R.string.post_menu_embed,
    PostMenuAction.REPORT to R.string.post_menu_report,
    PostMenuAction.COMMUNITY_NOTE_REQUEST to R.string.post_menu_community_note_request,
)
