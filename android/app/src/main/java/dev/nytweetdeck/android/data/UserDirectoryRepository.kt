package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.UserOption
import dev.nytweetdeck.android.model.TimelinePage
import dev.nytweetdeck.android.model.UserProfilePage
import dev.nytweetdeck.android.model.UserProfileTab
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.UserProfileParser
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class UserDirectoryRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val parser: UserProfileParser = UserProfileParser(),
    private val timelineParser: TimelineResponseParser = TimelineResponseParser(),
) {
    fun resolve(account: AccountSecrets, input: String, language: String = "ja"): UserOption {
        val username = input.trim().removePrefix("@")
        require(username.matches(Regex("[A-Za-z0-9_]{1,15}"))) {
            "有効なXユーザー名を入力してください。"
        }
        val body = graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            "userByScreenName",
            mapOf("screen_name" to username, "withGrokTranslatedBio" to false),
            language,
        )
        return parser.resolve(body, username)
    }

    fun profile(account: AccountSecrets, userId: String, language: String = "ja"): UserProfilePage {
        require(userId.matches(Regex("[0-9]{1,24}"))) { "XユーザーID形式が不正です。" }
        val credentials = XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken)
        val profileBody = graphQlExecutor.execute(
            credentials,
            "userByRestId",
            mapOf("userId" to userId),
            language,
        )
        val mutualFollowersBody = graphQlExecutor.execute(
            credentials,
            "followersYouKnow",
            mapOf(
                "userId" to userId,
                "count" to 20,
                "includePromotedContent" to false,
                "withGrokTranslatedBio" to false,
            ),
            language,
        )
        return parser.parseProfile(profileBody, userId, mutualFollowersBody)
    }

    fun timeline(
        account: AccountSecrets,
        userId: String,
        tab: UserProfileTab,
        cursor: String? = null,
        language: String = "ja",
    ): TimelinePage {
        require(userId.matches(Regex("[0-9]{1,24}"))) { "XユーザーID形式が不正です。" }
        val variables = linkedMapOf<String, Any?>(
            "userId" to userId,
            "count" to 20,
            "includePromotedContent" to false,
            "withVoice" to true,
        )
        cursor?.takeIf(String::isNotBlank)?.let { variables["cursor"] = it }
        val purpose = when (tab) {
            UserProfileTab.ALL -> "userPosts"
            UserProfileTab.POSTS -> "userOriginals"
            UserProfileTab.HIGHLIGHTS -> "userHighlights"
            UserProfileTab.REPLIES -> "userReplies"
            UserProfileTab.MEDIA -> "userMedia"
        }
        when (tab) {
            UserProfileTab.ALL, UserProfileTab.POSTS -> {
                variables["withQuickPromoteEligibilityTweetFields"] = false
            }
            UserProfileTab.REPLIES -> variables["withCommunity"] = true
            UserProfileTab.MEDIA -> {
                variables["withClientEventToken"] = false
                variables["withBirdwatchNotes"] = false
            }
            UserProfileTab.HIGHLIGHTS -> Unit
        }
        return timelineParser.parse(
            graphQlExecutor.execute(
                XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
                purpose,
                variables,
                language,
            ),
        )
    }
}
