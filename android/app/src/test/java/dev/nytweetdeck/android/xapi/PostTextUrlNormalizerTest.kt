package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Test

class PostTextUrlNormalizerTest {
    @Test
    fun expandsOrdinaryLinksAndPrefersTheUnwoundDestination() {
        val entities = listOf(
            PostUrlEntity(
                shortUrl = "https://t.co/docs",
                expandedUrl = "https://example.com/short",
                unwoundUrl = "https://example.com/original?a=1",
                kind = PostUrlEntityKind.LINK,
            ),
        )

        assertEquals(
            "資料 https://example.com/original?a=1",
            PostTextUrlNormalizer.normalize("資料 https://t.co/docs", entities),
        )
    }

    @Test
    fun removesMediaAndArticleRedirectsWithoutTouchingLookalikeHosts() {
        val entities = listOf(
            PostUrlEntity("https://t.co/media", null, null, PostUrlEntityKind.MEDIA),
            PostUrlEntity("https://t.co/article", null, null, PostUrlEntityKind.ARTICLE),
            PostUrlEntity(
                "https://not-t.co/link",
                "https://example.com/original",
                null,
                PostUrlEntityKind.LINK,
            ),
        )

        assertEquals(
            "本文 https://not-t.co/link",
            PostTextUrlNormalizer.normalize(
                "本文 https://t.co/media https://t.co/article https://not-t.co/link",
                entities,
            ),
        )
    }

    @Test
    fun keepsTheShortUrlWhenTheDestinationIsMissingOrUnsafe() {
        val entities = listOf(
            PostUrlEntity(
                "https://t.co/docs",
                "javascript:alert(1)",
                null,
                PostUrlEntityKind.LINK,
            ),
        )

        assertEquals(
            "https://t.co/docs",
            PostTextUrlNormalizer.normalize("https://t.co/docs", entities),
        )
    }

    @Test
    fun decodesSafeHtmlReferencesExactlyOnceWithoutUrlEntities() {
        assertEquals(
            "A < B > C & D < E > F \"Q\" 'x' &unknown; &lt;",
            PostTextUrlNormalizer.normalize(
                "A &lt; B &gt; C &amp; D &#60; E &#x3E; F &quot;Q&quot; &apos;x&apos; " +
                    "&unknown; &amp;lt;",
                emptyList(),
            ),
        )
    }

    @Test
    fun leavesInvalidNumericReferencesUnchanged() {
        assertEquals(
            "&#0; &#xD800; &#99999999;",
            PostTextUrlNormalizer.normalize("&#0; &#xD800; &#99999999;", emptyList()),
        )
    }
}
