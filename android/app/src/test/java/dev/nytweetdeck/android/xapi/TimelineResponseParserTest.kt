package dev.nytweetdeck.android.xapi

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineResponseParserTest {
    private val parser = TimelineResponseParser()

    @Test
    fun normalizesPostsMediaMetricsAndCursorFromGraphQlUrtResponse() {
        val page = parser.parse(fixture("timeline-response.json"))

        assertEquals("next-cursor", page.nextCursor)
        assertEquals(listOf("101", "100"), page.posts.map { it.id })
        val detailed = page.posts[1]
        assertTrue(detailed.text.contains("#NyTweetDeck"))
        assertEquals("ja", detailed.language)
        assertEquals("2018-10-10T20:19:24Z", detailed.createdAt)
        assertEquals("alice", detailed.author.username)
        assertTrue(detailed.author.verified)
        assertEquals(2L, detailed.replyCount)
        assertEquals(3L, detailed.repostCount)
        assertEquals(4L, detailed.quoteCount)
        assertEquals(5L, detailed.likeCount)
        assertEquals(6L, detailed.bookmarkCount)
        assertEquals(1234L, detailed.viewCount)
        assertTrue(detailed.liked)
        assertTrue(detailed.bookmarked)
        assertEquals("Pretranslated post #NyTweetDeck", detailed.preTranslated?.text)
        assertEquals("ja", detailed.preTranslated?.sourceLanguage)
        assertEquals("en", detailed.preTranslated?.targetLanguage)
        assertEquals("Grok", detailed.preTranslated?.provider)
        assertEquals("99", detailed.quotedPostId)
        assertEquals("引用元", detailed.quotedPost?.text)
        assertEquals(2, detailed.media.size)
        assertEquals("https://video.twimg.com/high.mp4", detailed.media[1].url)
        assertEquals(
            listOf("https://video.twimg.com/low.mp4", "https://video.twimg.com/high.mp4"),
            detailed.media[1].variants.map { it.url },
        )
        assertFalse(page.posts.any { it.id == "99" })
        assertEquals("100", page.posts[0].replyToPostId)
        assertEquals("parent_user", page.posts[0].replyToUsername)
    }

    @Test
    fun readsIdentityFromCurrentWebUserSchema() {
        val post = parser.parse(
            """
            {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"200",
            "legacy":{"full_text":"web &lt; schema &gt; &amp; safe","created_at":"2018-10-10T20:19:24Z"},
            "core":{"user_results":{"result":{"__typename":"User","rest_id":"42",
            "core":{"screen_name":"alice","name":"Alice &lt;3"},
            "avatar":{"image_url":"https://pbs.twimg.com/profile_images/alice.jpg"},
            "verification":{"verified":true}}}}}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("42", post.author.id)
        assertEquals("alice", post.author.username)
        assertEquals("web < schema > & safe", post.text)
        assertEquals("Alice <3", post.author.displayName)
        assertTrue(post.author.avatarUrl.orEmpty().endsWith("alice.jpg"))
        assertTrue(post.author.verified)
    }

    @Test
    fun readsPretranslationFromVisibilityResultWrapper() {
        val post = parser.parse(
            """
            {"result":{"__typename":"TweetWithVisibilityResults",
            "grok_translated_post_with_availability":{"is_available":true,"data":{
              "translation":"ラッパーの事前翻訳","source_language":"en",
              "destination_language":"ja"}},"tweet":{"__typename":"Tweet","rest_id":"204",
              "legacy":{"full_text":"wrapper original","lang":"en",
              "created_at":"2018-10-10T20:19:24Z"}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("ラッパーの事前翻訳", post.preTranslated?.text)
        assertEquals("ja", post.preTranslated?.targetLanguage)
    }

    @Test
    fun excludesPromotedTimelineEntries() {
        val page = parser.parse(
            """
            {"entries":[
              {"entryId":"promoted-tweet-1","content":{"promotedMetadata":{"advertiser_id":"1"},
                "itemContent":{"tweet_results":{"result":{"__typename":"Tweet","rest_id":"300",
                  "legacy":{"full_text":"sponsored","created_at":"2018-10-10T20:19:24Z"}}}}}},
              {"entryId":"tweet-301","content":{"itemContent":{"tweet_results":{"result":{
                "__typename":"Tweet","rest_id":"301",
                "legacy":{"full_text":"organic","created_at":"2018-10-10T20:19:24Z"}}}}}}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("301"), page.posts.map { it.id })
        assertEquals("organic", page.posts.single().text)
    }

    @Test
    fun readsIdentityFromBookmarksUserWrapper() {
        val post = parser.parse(
            """
            {
              "data": {
                "bookmark_timeline_v2": {
                  "timeline": {
                    "instructions": [{
                      "entries": [{
                        "content": {
                          "itemContent": {
                            "tweet_results": {
                              "result": {
                                "__typename": "Tweet",
                                "rest_id": "201",
                                "legacy": {
                                  "full_text": "saved post",
                                  "created_at": "2018-10-10T20:19:24Z"
                                },
                                "core": {
                                  "user_results": {
                                    "result": {
                                      "result": {
                                        "__typename": "User",
                                        "rest_id": "43",
                                        "core": {"screen_name": "bob", "name": "Bob"},
                                        "avatar": {"image_url": "https://pbs.twimg.com/bob.jpg"}
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }]
                    }]
                  }
                }
              }
            }
            """.trimIndent(),
        ).posts.single()

        assertEquals("43", post.author.id)
        assertEquals("bob", post.author.username)
        assertEquals("Bob", post.author.displayName)
        assertEquals("https://pbs.twimg.com/bob.jpg", post.author.avatarUrl)
    }

    @Test
    fun readsIdentityFromBookmarksSingularUserResult() {
        val post = parser.parse(
            """
            {"data":{"bookmark_timeline_v2":{"timeline":{"instructions":[{"entries":[{
            "content":{"itemContent":{"tweet_results":{"result":{"__typename":"Tweet",
            "rest_id":"202","legacy":{"full_text":"another saved post",
            "created_at":"2018-10-10T20:19:24Z"},"core":{"user_result":{"result":
            {"__typename":"User","rest_id":"44","legacy":{"screen_name":"carol",
            "name":"Carol","profile_image_url_https":"https://pbs.twimg.com/carol.jpg"}}}}}}}}}
            ]}]}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("44", post.author.id)
        assertEquals("carol", post.author.username)
        assertEquals("Carol", post.author.displayName)
        assertEquals("https://pbs.twimg.com/carol.jpg", post.author.avatarUrl)
    }

    @Test
    fun limitsBookmarksFallbackToTheTweetCoreAuthorArea() {
        val post = parser.parse(
            """
            {"data":{"bookmark_timeline_v2":{"tweet_results":{"result":{"__typename":"Tweet",
            "rest_id":"203","legacy":{"full_text":"saved schema variant",
            "created_at":"2018-10-10T20:19:24Z"},"core":{"account_reference":{"value":
            {"__typename":"User","rest_id":"45","core":{"screen_name":"dave","name":"Dave"},
            "avatar":{"image_url":"https://pbs.twimg.com/dave.jpg"}}}},"quoted_status_result":
            {"result":{"__typename":"Tweet","rest_id":"999","legacy":{"full_text":"quoted"},
            "core":{"user_results":{"result":{"__typename":"User","rest_id":"99","core":
            {"screen_name":"wrong","name":"Wrong"}}}}}}}}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("45", post.author.id)
        assertEquals("dave", post.author.username)
        assertEquals("Dave", post.author.displayName)
        assertEquals("https://pbs.twimg.com/dave.jpg", post.author.avatarUrl)
    }

    @Test
    fun rendersTheRetweetedSourceAsThePostAndKeepsOnlyTheReposterContext() {
        val post = parser.parse(
            """
            {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"300",
            "core":{"user_results":{"result":{"__typename":"User","rest_id":"30",
            "core":{"screen_name":"reposter","name":"Reposter"}}}},"legacy":{
            "full_text":"RT @origin: source text","created_at":"2019-01-02T00:00:00Z",
            "retweeted_status_result":{"result":{"__typename":"Tweet","rest_id":"250",
            "core":{"user_results":{"result":{"__typename":"User","rest_id":"25",
            "core":{"screen_name":"origin","name":"Original Author"},"avatar":{
            "image_url":"https://pbs.twimg.com/origin.jpg"}}}},"views":{"count":"99"},
            "legacy":{"full_text":"source text","lang":"en",
            "created_at":"2019-01-01T00:00:00Z","favorite_count":8,
            "retweet_count":4}}}}}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("250", post.id)
        assertEquals("source text", post.text)
        assertEquals("origin", post.author.username)
        assertEquals("reposter", post.repostedBy?.username)
        assertEquals(8L, post.likeCount)
        assertEquals(4L, post.repostCount)
        assertEquals(99L, post.viewCount)
    }

    @Test
    fun usesTheCompleteNoteTweetBodyForARetweetedSource() {
        val post = parser.parse(
            """
            {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"310",
            "core":{"user_results":{"result":{"__typename":"User","rest_id":"31",
            "core":{"screen_name":"reposter","name":"Reposter"}}}},"legacy":{
            "full_text":"RT @origin: shortened source…",
            "created_at":"2019-01-02T00:00:00Z","retweeted_status_result":{"result":{
            "__typename":"Tweet","rest_id":"251","core":{"user_results":{"result":{
            "__typename":"User","rest_id":"25","core":{"screen_name":"origin",
            "name":"Original Author"}}}},"legacy":{"full_text":"shortened source…",
            "lang":"en","created_at":"2019-01-01T00:00:00Z"},"note_tweet":{
            "note_tweet_results":{"result":{"text":
            "complete retweeted source body without an omitted ending"}}}}}}}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("251", post.id)
        assertEquals("complete retweeted source body without an omitted ending", post.text)
        assertEquals("origin", post.author.username)
        assertEquals("reposter", post.repostedBy?.username)
    }

    @Test
    fun normalizesAQuotedTweetForTheEmbeddedWebStyleCard() {
        val post = parser.parse(
            """
            {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"400",
            "core":{"user_results":{"result":{"__typename":"User","rest_id":"40",
            "core":{"screen_name":"quoter","name":"Quoter"}}}},"legacy":{
            "full_text":"my comment","lang":"en","created_at":"2019-01-02T00:00:00Z",
            "quoted_status_id_str":"399"},"quoted_status_result":{"result":{
            "__typename":"Tweet","rest_id":"399","core":{"user_results":{"result":{
            "__typename":"User","rest_id":"39","core":{"screen_name":"quoted",
            "name":"Quoted Author"},"avatar":{"image_url":"https://pbs.twimg.com/q.jpg"}}}},
            "grok_translated_post_with_availability":{"is_available":true,"data":{
            "translation":"quoted translation","source_language":"ja",
            "destination_language":"en"}},"legacy":{"full_text":"quoted text","lang":"ja",
            "created_at":"2019-01-01T00:00:00Z","extended_entities":{"media":[{
            "id_str":"photo-1","type":"photo","media_url_https":
            "https://pbs.twimg.com/quote.jpg"}]}}}}}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("my comment", post.text)
        assertEquals("399", post.quotedPostId)
        assertEquals("quoted", post.quotedPost?.author?.username)
        assertEquals("quoted text", post.quotedPost?.text)
        assertEquals("quoted translation", post.quotedPost?.preTranslated?.text)
        assertEquals("en", post.quotedPost?.preTranslated?.targetLanguage)
        assertEquals(1, post.quotedPost?.media?.size)
    }

    @Test
    fun usesCompleteNoteTweetBodiesForAQuoteAndItsEmbeddedSource() {
        val post = parser.parse(
            """
            {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"410",
            "core":{"user_results":{"result":{"__typename":"User","rest_id":"41",
            "core":{"screen_name":"quoter","name":"Quoter"}}}},"legacy":{
            "full_text":"shortened quote comment…","lang":"en",
            "created_at":"2019-01-02T00:00:00Z","quoted_status_id_str":"409"},
            "note_tweet":{"note_tweet_results":{"result":{"text":
            "complete quote comment without an omitted ending"}}},
            "quoted_status_result":{"result":{"__typename":"Tweet","rest_id":"409",
            "core":{"user_results":{"result":{"__typename":"User","rest_id":"39",
            "core":{"screen_name":"quoted","name":"Quoted Author"}}}},"legacy":{
            "full_text":"shortened quoted source…","lang":"en",
            "created_at":"2019-01-01T00:00:00Z"},"note_tweet":{
            "note_tweet_results":{"result":{"text":
            "complete quoted source body without an omitted ending"}}}}}}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("complete quote comment without an omitted ending", post.text)
        assertEquals("complete quoted source body without an omitted ending", post.quotedPost?.text)
    }

    @Test
    fun preservesInitialEngagementStateAndCommunityNoteDetails() {
        val post = parser.parse(
            """
            {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"500",
            "legacy":{"full_text":"post with context","lang":"en",
            "created_at":"2019-01-02T00:00:00Z","favorited":true,"retweeted":true},
            "birdwatch_pivot":{"title":{"text":"Community Note"},
            "note":{"data_v1":{"summary":{"text":"This image was taken in 2024."}}},
            "footer":{"text":"Rated helpful by readers"}}}}}}
            """.trimIndent(),
        ).posts.single()

        assertTrue(post.liked)
        assertTrue(post.reposted)
        assertEquals("Community Note", post.communityNote?.title)
        assertEquals("This image was taken in 2024.", post.communityNote?.text)
        assertEquals("Rated helpful by readers", post.communityNote?.footer)
    }

    @Test
    fun omitsMediaRedirectUrlsFromPostAndPretranslatedText() {
        val post = parser.parse(
            """
            {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"600",
            "legacy":{"full_text":"画像付きポスト https://t.co/media123","lang":"ja",
            "created_at":"2019-01-02T00:00:00Z","extended_entities":{"media":[{
            "id_str":"photo-600","type":"photo","url":"https://t.co/media123",
            "media_url_https":"https://pbs.twimg.com/media/photo600.jpg"}]}},
            "grok_translated_post_with_availability":{"is_available":true,"data":{
            "translation":"Post with image https://t.co/media123","source_language":"ja",
            "destination_language":"en"}}}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("画像付きポスト", post.text)
        assertEquals("Post with image", post.preTranslated?.text)
        assertEquals("https://pbs.twimg.com/media/photo600.jpg", post.media.single().url)
    }

    @Test
    fun normalizesAnXArticleAndOmitsItsRedirectUrl() {
        val post = parser.parse(
            """
            {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"700",
            "legacy":{"full_text":"https://t.co/article700","lang":"ja",
            "created_at":"2019-01-02T00:00:00Z","entities":{"urls":[{
            "url":"https://t.co/article700",
            "expanded_url":"https://x.com/i/article/701"}]}},"article":{"article_results":{
            "result":{"rest_id":"701","title":"NyTweetDeckの記事",
            "preview_text":"記事の概要です。","plain_text":"最初の段落。\n\n全文の続き。",
            "cover_media":{"media_info":{"original_img_url":
            "https://pbs.twimg.com/media/article-cover.jpg"}}}}}}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("", post.text)
        assertEquals("701", post.article?.id)
        assertEquals("NyTweetDeckの記事", post.article?.title)
        assertEquals("記事の概要です。", post.article?.previewText)
        assertEquals("最初の段落。\n\n全文の続き。", post.article?.body)
        assertEquals("https://pbs.twimg.com/media/article-cover.jpg", post.article?.coverImageUrl)
        assertEquals("https://x.com/i/article/701", post.article?.url)
    }

    @Test
    fun expandsOrdinaryTcoLinksInPostNoteTweetAndPretranslation() {
        val post = parser.parse(
            """
            {"result":{"__typename":"Tweet","rest_id":"800",
              "legacy":{"full_text":"本文 https://t.co/main","lang":"ja",
                "created_at":"2019-01-02T00:00:00Z","entities":{"urls":[{
                  "url":"https://t.co/main","display_url":"example.com/readable",
                  "expanded_url":"https://example.com/expanded",
                  "unwound_url":"https://example.com/original?from=x"}]}},
              "grok_translated_post_with_availability":{"is_available":true,"data":{
                "translation":"Body https://t.co/main","source_language":"ja",
                "destination_language":"en"}}}}
            """.trimIndent(),
        ).posts.single()
        val notePost = parser.parse(
            """
            {"result":{"__typename":"Tweet","rest_id":"799",
              "legacy":{"full_text":"short","created_at":"2019-01-01T00:00:00Z"},
              "note_tweet":{"note_tweet_results":{"result":{
                "text":"長文 https://t.co/note","entity_set":{"urls":[{
                  "url":"https://t.co/note",
                  "expanded_url":"https://docs.example.org/guide"}]}
              }}}}}
            """.trimIndent(),
        ).posts.single()

        assertEquals("本文 https://example.com/original?from=x", post.text)
        assertEquals("https://example.com/original?from=x", post.links.single().url)
        assertEquals("example.com/readable", post.links.single().displayText)
        assertEquals("Body https://example.com/original?from=x", post.preTranslated?.text)
        assertEquals("長文 https://docs.example.org/guide", notePost.text)
    }

    @Test
    fun preservesResponseOrderConversationSectionAndDeduplicatesPosts() {
        val body = """
            {"data":{"threaded_conversation_with_injections_v2":{"instructions":[{
            "type":"TimelineAddEntries","entries":[{"entryId":"conversationthread-1",
            "content":{"entryType":"TimelineTimelineModule","displayType":"VerticalConversation",
            "clientEventInfo":{"details":{"conversationDetails":{"conversationSection":"LowQuality"}}},
            "items":[{"entryId":"reply-1","item":{"itemContent":{"tweet_results":{"result":{"__typename":"Tweet",
            "rest_id":"701","legacy":{"full_text":"first native reply",
            "created_at":"2019-01-01T00:00:00Z"}}}},"clientEventInfo":{"details":{
            "conversationDetails":{"conversationSection":"LowQuality"}}}}}]}},
            {"entryId":"conversationthread-2","content":{"entryType":"TimelineTimelineModule",
            "displayType":"VerticalConversation","clientEventInfo":{"details":{"conversationDetails":{
            "conversationSection":"HighQuality"}}},"items":[{"entryId":"reply-2","item":{"itemContent":{
            "tweet_results":{"result":{"__typename":"Tweet","rest_id":"702","legacy":{
            "full_text":"second native reply","created_at":"2020-01-01T00:00:00Z"}}}}}}]}},
            {"entryId":"duplicate-701","content":{"itemContent":{"tweet_results":{"result":{"__typename":"Tweet",
            "rest_id":"701","legacy":{"full_text":"duplicate must be ignored",
            "created_at":"2021-01-01T00:00:00Z"}}}}}}
            ]}]}}}
        """.trimIndent()

        val responseOrder = parser.parseInResponseOrder(body)
        assertEquals(listOf("701", "702"), responseOrder.posts.map { it.id })
        assertEquals("first native reply", responseOrder.posts[0].text)
        assertEquals("LowQuality", responseOrder.posts[0].conversationSection)
        assertEquals("HighQuality", responseOrder.posts[1].conversationSection)

        val chronological = parser.parse(body)
        assertEquals(listOf("702", "701"), chronological.posts.map { it.id })
    }

    @Test
    fun convertsMalformedJsonToXApiException() {
        val exception = assertThrows(XApiException::class.java) {
            parser.parse("{not-json")
        }

        assertEquals("タイムライン応答を解析できません。", exception.message)
        assertNotNull(exception.cause)
    }

    @Test
    fun returnsNoPostsForNonTweetPayload() {
        val page = parser.parse("{\"data\":{\"viewer\":null}}")

        assertTrue(page.posts.isEmpty())
        assertNull(page.nextCursor)
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/fixtures/$name"),
    ).use { input ->
        String(input.readBytes(), StandardCharsets.UTF_8)
    }
}
