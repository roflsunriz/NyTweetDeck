package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.CommunityNotePage
import dev.nytweetdeck.android.model.CommunityNoteDetail
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.NotificationResponseParser
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class CommunityNoteRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val notificationParser: NotificationResponseParser = NotificationResponseParser(),
    private val timelineParser: TimelineResponseParser = TimelineResponseParser(),
) {
    // Serial requests and a bounded account/language cache prevent a column burst from flooding X.
    private val cache = object : LinkedHashMap<Triple<String, String, String>, CommunityNoteDetail>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, String>, CommunityNoteDetail>?) = size > 256
    }

    @Synchronized
    fun loadNote(account: AccountSecrets, noteId: String, language: String): CommunityNoteDetail {
        require(NOTE_ID.matches(noteId)) { "コミュニティノートIDの形式が不正です。" }
        val key = Triple(account.accountId, noteId, language)
        cache[key]?.let { return it }
        val body = graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            "communityNote", mapOf("note_id" to noteId), language,
        )
        return notificationParser.parseCommunityNote(body, noteId, language).also {
            if (it.translation != null) cache[key] = it
        }
    }

    fun load(account: AccountSecrets, noteId: String, language: String = "ja"): CommunityNotePage {
        require(NOTE_ID.matches(noteId)) { "コミュニティノートIDの形式が不正です。" }
        val credentials = XSessionCredentials(
            account.webBearerToken,
            account.authToken,
            account.csrfToken,
        )
        val detail = loadNote(account, noteId, language)
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
