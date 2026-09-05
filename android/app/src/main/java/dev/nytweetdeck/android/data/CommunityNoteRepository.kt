package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.CommunityNotePage
import dev.nytweetdeck.android.model.CommunityNoteDetail
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.NotificationResponseParser
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials
import dev.nytweetdeck.android.xapi.AuthenticatedRestClient
import dev.nytweetdeck.android.xapi.parseLiveNoteTranslation
import dev.nytweetdeck.android.text.hasTranslatableText

fun interface XCommunityNoteTranslationEndpoint {
    fun translate(account: AccountSecrets, noteId: String, language: String): AuthenticatedRestClient.RestResult
}

class CommunityNoteRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val notificationParser: NotificationResponseParser = NotificationResponseParser(),
    private val timelineParser: TimelineResponseParser = TimelineResponseParser(),
    private val liveTranslation: XCommunityNoteTranslationEndpoint? = null,
) {
    private val memory = TranslationMemory<Triple<String, String, String>, CommunityNoteDetail>(256)
    private val translatedMemory = TranslationMemory<Triple<String, String, String>, CommunityNoteDetail>(256)
    private val upstreamLock = Any()

    fun loadNote(account: AccountSecrets, noteId: String, language: String): CommunityNoteDetail {
        require(NOTE_ID.matches(noteId)) { "コミュニティノートIDの形式が不正です。" }
        val target = language.trim().replace('_', '-').lowercase(java.util.Locale.ROOT)
        val key = Triple(account.accountId, noteId, target)
        return memory.getOrLoad(key, cacheable = { it.translation != null }) {
            // Keep note requests serial while allowing completed cache hits during a request.
            synchronized(upstreamLock) {
                val body = graphQlExecutor.execute(
                    XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
                    "communityNote", mapOf("note_id" to noteId), target,
                )
                notificationParser.parseCommunityNote(body, noteId, target)
            }
        }
    }

    fun translateNote(account: AccountSecrets, noteId: String, language: String): CommunityNoteDetail {
        val target = language.trim().replace('_', '-').lowercase(java.util.Locale.ROOT)
        return translatedMemory.getOrLoad(Triple(account.accountId, noteId, target), cacheable = { it.translation != null }) {
            val detail = loadNote(account, noteId, target)
            val endpoint = liveTranslation
            if (detail.translation != null || endpoint == null || !hasTranslatableText(detail.text) ||
                detail.language?.substringBefore('-')?.equals(target.substringBefore('-'), true) == true
            ) detail else synchronized(upstreamLock) {
                val response = endpoint.translate(account, noteId, target)
                detail.copy(translation = parseLiveNoteTranslation(response.body, noteId, detail.language, target))
            }
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
