package dev.nytweetdeck.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PostCardFormattingTest {
    @Test
    fun parsesIsoAndXWebCreatedAtFormats() {
        val expected = Instant.parse("2026-08-29T00:00:00Z")

        assertEquals(expected, parsePostInstant("2026-08-29T00:00:00Z"))
        assertEquals(expected, parsePostInstant("Sat Aug 29 00:00:00 +0000 2026"))
    }

    @Test
    fun rejectsMissingOrMalformedTime() {
        assertNull(parsePostInstant(null))
        assertNull(parsePostInstant("not-a-time"))
    }

    @Test
    fun replyActionUsesASpeechBubbleInsteadOfAReplyArrow() {
        assertSame(Icons.Outlined.ChatBubbleOutline, replyActionIcon())
    }

    @Test
    fun segmentsHttpLinksHashtagsAndTrailingPunctuation() {
        assertEquals(
            listOf(
                PostTextSegment.Plain("参照 "),
                PostTextSegment.Url("https://example.test/a(b)", "https://example.test/a(b)"),
                PostTextSegment.Plain(")、 "),
                PostTextSegment.Hashtag("#NyTD"),
            ),
            postTextSegments("参照 https://example.test/a(b))、 #NyTD"),
        )
    }

    @Test
    fun rejectsNonHttpAndMalformedLinks() {
        assertEquals(
            listOf(PostTextSegment.Plain("javascript:alert(1) https://")),
            postTextSegments("javascript:alert(1) https://"),
        )
    }
}
