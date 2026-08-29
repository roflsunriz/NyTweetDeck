package dev.nytweetdeck.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.NotificationActor

@Composable
internal fun FollowNotificationUsersDialog(
    actors: List<NotificationActor>,
    onActorClick: (NotificationActor) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.follow_users_title)) },
        text = {
            if (actors.isEmpty()) {
                Text(stringResource(R.string.follow_users_empty))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .testTag("follow-users"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(actors, key = ::actorIdentity) { actor ->
                        FollowActorRow(actor, onActorClick)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-follow-users")) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun FollowActorRow(
    actor: NotificationActor,
    onActorClick: (NotificationActor) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onActorClick(actor) }
            .padding(vertical = 10.dp)
            .testTag("follow-user-" + actorIdentity(actor).hashCode()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActorAvatar(actor, Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = actorDisplayName(actor),
                fontWeight = FontWeight.SemiBold,
            )
            actor.username?.trim()?.removePrefix("@")?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = "@$it",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ActorAvatar(actor: NotificationActor, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .testTag("notification-actor-" + actorIdentity(actor).hashCode()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = actorDisplayName(actor).take(1).uppercase(),
            fontWeight = FontWeight.Bold,
        )
        actor.avatarUrl?.let { avatarUrl ->
            AsyncImage(
                model = safeImageUrl(avatarUrl),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
internal fun actorDisplayName(actor: NotificationActor): String =
    actor.displayName?.takeIf(String::isNotBlank)
        ?: actor.username?.takeIf(String::isNotBlank)
        ?: actor.id?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.notification_unknown_actor)

internal fun actorIdentity(actor: NotificationActor): String =
    actor.id?.takeIf(String::isNotBlank)
        ?: actor.username?.takeIf(String::isNotBlank)
        ?: actor.displayName?.takeIf(String::isNotBlank)
        ?: "unknown"
