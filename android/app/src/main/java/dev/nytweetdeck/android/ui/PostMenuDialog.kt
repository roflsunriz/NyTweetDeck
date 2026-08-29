package dev.nytweetdeck.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("post-menu"),
        title = { Text(stringResource(R.string.post_menu_title)) },
        text = {
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-post-menu")) {
                Text(stringResource(R.string.close))
            }
        },
    )
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
