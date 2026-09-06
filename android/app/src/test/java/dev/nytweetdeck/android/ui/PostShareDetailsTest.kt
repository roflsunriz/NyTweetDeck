package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.EmbeddedPost
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.Post
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostShareDetailsTest {
    private fun author() = Author(
        id = "42",
        username = "alice",
        displayName = "Alice",
        avatarUrl = null,
        verified = false,
    )

    private fun post() = Post(
        id = "123",
        text = "本文テスト",
        language = "ja",
        createdAt = "2026-09-06T12:00:00Z",
        author = author(),
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
        replyToPostId = null,
        replyToUsername = null,
        quotedPostId = null,
        quotedPost = null,
        communityNote = null,
        preTranslated = null,
        article = null,
        media = emptyList(),
    )

    @Test
    fun formatsBasicDetailedShare() {
        val text = formatDetailedPostShare(
            post().copy(
                media = listOf(Media(id = "m1", type = "photo", url = "https://pbs.twimg.com/a.jpg", previewUrl = null)),
            ),
            absoluteTime = "2026-09-06 21:00:00",
            relativeTime = "3時間",
        )
        val lines = text!!.split("\n")
        assertEquals("Alice@alice", lines.first())
        assertTrue(lines.contains("本文テスト"))
        assertTrue(lines.contains("https://pbs.twimg.com/a.jpg"))
        assertTrue(lines.contains("2026-09-06 21:00:00 (3時間)"))
        assertEquals("https://x.com/i/status/123", lines.last())
    }

    @Test
    fun quotesQuotedPostAndExcludesQuotedMedia() {
        val text = formatDetailedPostShare(
            post(),
            post().quotedPost,
            absoluteTime = "2026-09-06 21:00:00",
            relativeTime = "3時間",
            postUrl = "https://x.com/i/status/123",
        )
        assertTrue(text.contains("Alice@alice"))
        assertTrue(text.endsWith("https://x.com/i/status/123"))
    }

    @Test
    fun fullPostWithQuoteUsesBlockquoteAndOnlyOriginalMedia() {
        val quoted = EmbeddedPost(
            id = "122",
            text = "引用本文\n2行目",
            language = "ja",
            createdAt = "2026-09-05T12:00:00Z",
            author = Author(id = "24", username = "quoted", displayName = "Quoted", avatarUrl = null, verified = false),
            preTranslated = null,
            article = null,
            media = listOf(Media(id = "q1", type = "photo", url = "https://pbs.twimg.com/quote.jpg", previewUrl = null)),
            links = emptyList(),
        )
        val target = post().copy(
            quotedPostId = "122",
            quotedPost = quoted,
            media = listOf(Media(id = "m1", type = "photo", url = "https://pbs.twimg.com/a.jpg", previewUrl = null)),
        )
        val text = formatDetailedPostShare(target, "2026-09-06 21:00:00", "3時間")!!
        assertTrue(text.contains("Quoted@quoted"))
        assertTrue(text.contains("> 引用本文"))
        assertTrue(text.contains("> 2行目"))
        assertFalse(text.contains("https://pbs.twimg.com/quote.jpg"))
        assertTrue(text.contains("https://pbs.twimg.com/a.jpg"))
        assertEquals("https://x.com/i/status/123", text.split("\n").last())
    }

    @Test
    fun translatedShareUsesPretranslationAsIs() {
        val target = post().copy(
            text = "Original body",
            preTranslated = dev.nytweetdeck.android.model.Translation(
                text = "翻訳された本文",
                sourceLanguage = "en",
                targetLanguage = "ja",
                provider = "Grok",
            ),
            quotedPost = EmbeddedPost(
                id = "122",
                text = "Quoted original",
                language = "en",
                createdAt = null,
                author = Author(id = "24", username = "quoted", displayName = "Quoted", avatarUrl = null, verified = false),
                preTranslated = dev.nytweetdeck.android.model.Translation(
                    text = "翻訳された引用",
                    sourceLanguage = "en",
                    targetLanguage = "ja",
                    provider = "Grok",
                ),
                article = null,
                media = emptyList(),
                links = emptyList(),
            ),
        )
        val text = formatDetailedPostShare(target, "2026-09-06 21:00:00", "3時間", translated = true)!!
        assertTrue(text.contains("翻訳された本文"))
        assertFalse(text.contains("Original body"))
        assertTrue(text.contains("> 翻訳された引用"))
        assertFalse(text.contains("Quoted original"))
        assertEquals("https://x.com/i/status/123", text.split("\n").last())
    }

    @Test
    fun translatedShareFallsBackToOriginalWithoutPretranslation() {
        val text = formatDetailedPostShare(post(), "2026-09-06 21:00:00", "3時間", translated = true)!!
        assertTrue(text.contains("本文テスト"))
    }

    @Test
    fun formatsAbsoluteTimeInSystemZone() {
        val absolute = formatShareAbsoluteTime(
            "2026-09-06T12:00:00Z",
            ZoneId.of("Asia/Tokyo"),
        )
        assertEquals("2026-09-06 21:00:00", absolute)
    }
}
