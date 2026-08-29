package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.nytweetdeck.android.model.DirectMessage
import dev.nytweetdeck.android.model.DirectMessageColumnState
import dev.nytweetdeck.android.model.DirectMessagePage
import dev.nytweetdeck.android.model.Notification
import dev.nytweetdeck.android.model.NotificationActor
import dev.nytweetdeck.android.model.NotificationColumnState
import dev.nytweetdeck.android.model.NotificationPage
import dev.nytweetdeck.android.model.TimelineLoadStatus
import dev.nytweetdeck.android.ui.DirectMessageBody
import dev.nytweetdeck.android.ui.NotificationBody
import dev.nytweetdeck.android.ui.FollowNotificationUsersDialog
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SecondaryColumnsUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dmAvatarSenderTimeAndNotificationClickAreReachableOnAquos() {
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                DirectMessageBody(
                    state = DirectMessageColumnState(
                        TimelineLoadStatus.READY,
                        DirectMessagePage(
                            listOf(
                                DirectMessage(
                                    "dm-1", "conversation", "7", "送信者", "sender",
                                    null, "DM本文", 1_788_000_000_000,
                                ),
                            ),
                            null,
                        ),
                    ),
                    onRetry = {},
                    scrollPosition = null,
                    onScrollPositionChanged = { _, _, _ -> },
                    onLoadMore = {},
                )
            }
        }
        composeRule.onNodeWithTag("message-dm-1").assertIsDisplayed()

        var clicked: Notification? = null
        val notification = Notification(
            "follow-1", "follow", "2人にフォローされました", null, null,
            listOf(
                NotificationActor("1", "one", "One", null),
                NotificationActor("2", "two", "Two", null),
            ),
            emptyList(),
        )
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                NotificationBody(
                    state = NotificationColumnState(
                        TimelineLoadStatus.READY,
                        NotificationPage(listOf(notification), emptyList(), null),
                    ),
                    onRetry = {},
                    scrollPosition = null,
                    onScrollPositionChanged = { _, _, _ -> },
                    onLoadMore = {},
                    onNotificationClick = { clicked = it },
                )
            }
        }
        composeRule.onNodeWithTag("notification-item-${"follow-1".hashCode()}").performClick()
        assertEquals("follow-1", clicked?.id)

        var actorId: String? = null
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                FollowNotificationUsersDialog(
                    actors = notification.actors,
                    onActorClick = { actorId = it.id },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag("follow-users").assertIsDisplayed()
        composeRule.onNodeWithTag("follow-user-${"1".hashCode()}").performClick()
        assertEquals("1", actorId)
    }
}
