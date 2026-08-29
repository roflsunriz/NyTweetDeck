package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.CommunityNotePage
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.NotificationResponseParser
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class CommunityNoteRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val notificationParser: NotificationResponseParser = NotificationResponseParser(),
    private val timelineParser: TimelineResponseParser = TimelineResponseParser(),
) {
    fun load(account: AccountSecrets, noteId: String, language: String = "ja"): CommunityNotePage {
        require(NOTE_ID.matches(noteId)) { "コミュニティノートIDの形式が不正です。" }
        val credentials = XSessionCredentials(
            account.webBearerToken,
            account.authToken,
            account.csrfToken,
        )
        val noteBody = graphQlExecutor.execute(
            credentials,
            "communityNote",
            mapOf("note_id" to noteId),
            language,
        )
        val detail = notificationParser.parseCommunityNote(noteBody, noteId)
        val postBody = graphQlExecutor.execute(
            credentials,
            "postDetail",
            mapOf(
                "tweetId" to detail.targetPostId,
                "withCommunity" to false,
                "includePromotedContent" to false,
                "withVoice" to false,
            ),
            language,
        )
        val post = timelineParser.parse(postBody).posts.firstOrNull { it.id == detail.targetPostId }
            ?: error("コミュニティノートの対象ポスト応答にポストがありません。")
        return CommunityNotePage(detail, post)
    }

    private companion object {
        val NOTE_ID = Regex("[0-9]{1,24}")
    }
}
