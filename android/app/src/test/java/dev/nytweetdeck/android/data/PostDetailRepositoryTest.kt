package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.model.ReplyQuality
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.XApiException
import dev.nytweetdeck.android.xapi.XSessionCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PostDetailRepositoryTest {
    @Test
    fun loadsFocalAndRepliesInConversationResponseOrderWithQualityHints() {
        val executor = RecordingGraphQlExecutor(
            detail = detailResponse("123"),
            conversation = conversationResponse(includeFocal = true),
        )
        val repository = PostDetailRepository(executor)

        val page = repository.load(
            account = account(),
            postId = "123",
            cursor = "cursor-request",
            language = "ja-JP",
            replySort = " likes ",
        )

        assertEquals("123", page.post.id)
        assertEquals(RankingMode.LIKES, page.rankingMode)
        assertEquals("cursor-next", page.nextCursor)
        assertEquals(listOf("701", "702", "703"), page.replies.map { reply -> reply.post.id })
        assertEquals(
            listOf(
                ReplyQuality.LOW_QUALITY,
                ReplyQuality.ABUSIVE_QUALITY,
                ReplyQuality.HIGH_QUALITY,
            ),
            page.replies.map { reply -> reply.quality },
        )
        assertTrue(page.replies[0].quality.isDeemphasized)
        assertTrue(page.replies[1].quality.isDeemphasized)
        assertEquals("LowQuality", page.replies[0].post.conversationSection)

        assertEquals(2, executor.calls.size)
        assertEquals("postDetail", executor.calls[0].purpose)
        assertEquals("conversation", executor.calls[1].purpose)
        assertSame(executor.calls[0].credentials, executor.calls[1].credentials)
        assertEquals("ja-JP", executor.calls[0].language)
        assertEquals(
            mapOf(
                "tweetId" to "123",
                "withCommunity" to false,
                "includePromotedContent" to false,
                "withVoice" to false,
            ),
            executor.calls[0].variables,
        )
        assertEquals(
            mapOf(
                "focalTweetId" to "123",
                "isReaderMode" to false,
                "rankingMode" to "Likes",
                "includePromotedContent" to false,
                "withCommunity" to true,
                "withQuickPromoteEligibilityTweetFields" to false,
                "withBirdwatchNotes" to true,
                "withVoice" to true,
                "cursor" to "cursor-request",
            ),
            executor.calls[1].variables,
        )
    }

    @Test
    fun fallsBackToTheConversationFocalPostWhenDetailPayloadDoesNotContainIt() {
        val repository = PostDetailRepository(
            RecordingGraphQlExecutor(
                detail = """{"data":{"tweet":{"result":null}}}""",
                conversation = conversationResponse(includeFocal = true),
            ),
        )

        val page = repository.load(account(), "123")

        assertEquals("123", page.post.id)
        assertEquals(listOf("701", "702", "703"), page.replies.map { reply -> reply.post.id })
    }

    @Test
    fun mapsOnlyNativeRankingModesAndRejectsBadIdsBeforeNetwork() {
        assertEquals(RankingMode.RELEVANCE, RankingMode.fromReplySort(null))
        assertEquals(RankingMode.RECENCY, RankingMode.fromReplySort(" RECENCY "))
        assertEquals(RankingMode.LIKES, RankingMode.fromReplySort("likes"))
        assertThrows(IllegalArgumentException::class.java) {
            RankingMode.fromReplySort("popular")
        }

        val executor = RecordingGraphQlExecutor(detailResponse("123"), conversationResponse(true))
        val repository = PostDetailRepository(executor)
        assertThrows(IllegalArgumentException::class.java) {
            repository.load(account(), "not-a-post-id")
        }
        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun rejectsResponsesThatDoNotContainTheRequestedFocalPost() {
        val repository = PostDetailRepository(
            RecordingGraphQlExecutor(
                detail = detailResponse("999"),
                conversation = conversationResponse(includeFocal = false),
            ),
        )

        val error = assertThrows(XApiException::class.java) {
            repository.load(account(), "123")
        }

        assertEquals(502, error.statusCode)
    }

    private fun account() = AccountSecrets(
        "7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7",
    )

    private fun detailResponse(postId: String): String = tweet(postId, "focal post")
        .let { result -> """{"data":{"tweet":{"result":$result}}}""" }

    private fun conversationResponse(includeFocal: Boolean): String {
        val entries = buildList {
            add(conversationEntry("701", "first reply", "LowQuality", "123"))
            if (includeFocal) {
                add(conversationEntry("123", "focal fallback", "HighQuality", null))
            }
            add(conversationEntry("702", "second reply", "AbusiveQuality", "123"))
            add(conversationEntry("703", "third reply", "HighQuality", "123"))
            add("""{"entryId":"cursor-bottom-1","content":{"value":"cursor-next"}}""")
        }
        return """{"data":{"threaded_conversation_with_injections_v2":{"instructions":[{"entries":[${entries.joinToString(",")}]}]}}}"""
    }

    private fun conversationEntry(
        id: String,
        text: String,
        section: String,
        inReplyTo: String?,
    ): String = """
        {"entryId":"conversation-$id","content":{
        "clientEventInfo":{"details":{"conversationDetails":{"conversationSection":"$section"}}},
        "itemContent":{"tweet_results":{"result":${tweet(id, text, inReplyTo)}}}}}
    """.trimIndent().replace("\n", "")

    private fun tweet(id: String, text: String, inReplyTo: String? = null): String {
        val replyField = inReplyTo?.let { value -> ",\"in_reply_to_status_id_str\":\"$value\"" }.orEmpty()
        return """{"__typename":"Tweet","rest_id":"$id","legacy":{"full_text":"$text"$replyField}}"""
    }

    private class RecordingGraphQlExecutor(
        private val detail: String,
        private val conversation: String,
    ) : GraphQlExecutor {
        val calls = mutableListOf<Call>()

        override fun execute(
            credentials: XSessionCredentials,
            purpose: String,
            variables: Map<String, Any?>,
            language: String,
        ): String {
            calls += Call(credentials, purpose, variables.toMap(), language)
            return when (purpose) {
                "postDetail" -> detail
                "conversation" -> conversation
                else -> throw IllegalArgumentException("unexpected purpose: $purpose")
            }
        }
    }

    private data class Call(
        val credentials: XSessionCredentials,
        val purpose: String,
        val variables: Map<String, Any?>,
        val language: String,
    )
}
