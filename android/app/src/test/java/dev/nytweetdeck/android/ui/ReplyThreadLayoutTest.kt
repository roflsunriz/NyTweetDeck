package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.ConversationReply
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.ReplyQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyThreadLayoutTest {
    @Test
    fun preservesResponseOrderAndAnnotatesDirectAndNestedReplies() {
        val replies = listOf(
            reply("B", "A"),
            reply("C", "A"),
            reply("D", "A", ReplyQuality.LOW_QUALITY),
            reply("E", "B"),
            reply("F", "E"),
        )

        val layout = buildReplyThreadLayout("A", replies)

        assertEquals(listOf("B", "C", "D", "E", "F"), layout.map { it.reply.post.id })
        assertEquals(listOf(0, 0, 0, 1, 2), layout.map { it.depth })
        assertEquals(listOf("B"), layout[3].ancestorReplyIds)
        assertEquals(listOf("B", "E"), layout[4].ancestorReplyIds)
        assertTrue(layout[0].hasDescendants)
        assertTrue(layout[3].hasDescendants)
        assertFalse(layout[1].hasDescendants)
        assertSame(replies[2], layout[2].reply)
        assertEquals(ReplyQuality.LOW_QUALITY, layout[2].reply.quality)
    }

    @Test
    fun missingParentsSelfReferencesAndCyclesFlattenSafely() {
        val layout = buildReplyThreadLayout(
            "A",
            listOf(
                reply("missing", "unknown"),
                reply("self", "self"),
                reply("cycle-1", "cycle-2"),
                reply("cycle-2", "cycle-1"),
            ),
        )

        assertEquals(listOf(0, 0, 0, 0), layout.map { it.depth })
        assertTrue(layout.none { it.hasDescendants })
    }

    @Test
    fun overDeepInputStopsAtTheVisualCapWithoutReordering() {
        val replies = (0 until 100).map { index ->
            reply(index.toString(), if (index == 0) "A" else (index - 1).toString())
        }

        val layout = buildReplyThreadLayout("A", replies)

        assertEquals((0 until 100).map(Int::toString), layout.map { it.reply.post.id })
        assertTrue(layout.all { it.depth <= MAX_REPLY_VISUAL_DEPTH })
        assertEquals(MAX_REPLY_VISUAL_DEPTH, layout.last().depth)
    }

    @Test
    fun appendingAPageOnlyAddsAnnotationsAndKeepsEarlierResponseOrder() {
        val firstPage = listOf(reply("B", "A"), reply("C", "A"))
        val before = buildReplyThreadLayout("A", firstPage)
        val after = buildReplyThreadLayout("A", firstPage + reply("E", "B"))

        assertEquals(listOf("B", "C"), before.map { it.reply.post.id })
        assertEquals(listOf("B", "C", "E"), after.map { it.reply.post.id })
        assertFalse(before.first().hasDescendants)
        assertTrue(after.first().hasDescendants)
        assertEquals(1, after.last().depth)
    }

    private fun reply(
        id: String,
        parentId: String?,
        quality: ReplyQuality = ReplyQuality.HIGH_QUALITY,
    ) = ConversationReply(
        post = Post(
            id = id,
            text = "reply-$id",
            language = "en",
            createdAt = null,
            author = Author(id, "user$id", "User $id", null, false),
            repostedBy = null,
            conversationSection = null,
            replyCount = 0,
            repostCount = 0,
            quoteCount = 0,
            likeCount = 0,
            bookmarkCount = 0,
            viewCount = 0,
            liked = false,
            reposted = false,
            bookmarked = false,
            replyToPostId = parentId,
            replyToUsername = null,
            quotedPostId = null,
            quotedPost = null,
            communityNote = null,
            preTranslated = null,
            article = null,
            media = emptyList(),
        ),
        quality = quality,
    )
}
