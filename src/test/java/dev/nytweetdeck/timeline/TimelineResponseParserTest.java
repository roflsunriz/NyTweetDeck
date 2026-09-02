package dev.nytweetdeck.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TimelineResponseParserTest {

    private final TimelineResponseParser parser =
            new TimelineResponseParser(JsonMapper.builder().build());

    @Test
    void normalizesPostsMediaMetricsAndCursorFromGraphQlUrtResponse() throws Exception {
        String body;
        try (var input = getClass().getResourceAsStream("/fixtures/timeline-response.json")) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        var page = parser.parse(body);

        assertThat(page.nextCursor()).isEqualTo("next-cursor");
        assertThat(page.posts()).hasSize(2);
        assertThat(page.posts()).extracting(TimelinePage.Post::id).containsExactly("101", "100");
        var detailed = page.posts().get(1);
        assertThat(detailed.text()).contains("#NyTweetDeck");
        assertThat(detailed.language()).isEqualTo("ja");
        assertThat(detailed.createdAt()).isEqualTo("2018-10-10T20:19:24Z");
        assertThat(detailed.author().username()).isEqualTo("alice");
        assertThat(detailed.author().verified()).isTrue();
        assertThat(detailed.replyCount()).isEqualTo(2);
        assertThat(detailed.repostCount()).isEqualTo(3);
        assertThat(detailed.quoteCount()).isEqualTo(4);
        assertThat(detailed.likeCount()).isEqualTo(5);
        assertThat(detailed.bookmarkCount()).isEqualTo(6);
        assertThat(detailed.viewCount()).isEqualTo(1234);
        assertThat(detailed.liked()).isTrue();
        assertThat(detailed.bookmarked()).isTrue();
        assertThat(detailed.preTranslated()).isNotNull();
        assertThat(detailed.preTranslated().text()).isEqualTo("Pretranslated post #NyTweetDeck");
        assertThat(detailed.preTranslated().sourceLanguage()).isEqualTo("ja");
        assertThat(detailed.preTranslated().targetLanguage()).isEqualTo("en");
        assertThat(detailed.preTranslated().provider()).isEqualTo("Grok");
        assertThat(detailed.quotedPostId()).isEqualTo("99");
        assertThat(detailed.quotedPost()).isNotNull();
        assertThat(detailed.quotedPost().text()).isEqualTo("引用元");
        assertThat(detailed.media()).hasSize(2);
        assertThat(detailed.media().get(1).url()).isEqualTo("https://video.twimg.com/high.mp4");
        assertThat(detailed.media().get(1).variants())
                .extracting(variant -> variant.url())
                .containsExactly(
                        "https://video.twimg.com/low.mp4",
                        "https://video.twimg.com/high.mp4");
        assertThat(page.posts()).extracting(TimelinePage.Post::id).doesNotContain("99");
        assertThat(page.posts().get(0).replyToPostId()).isEqualTo("100");
        assertThat(page.posts().get(0).replyToUsername()).isEqualTo("parent_user");
    }

    @Test
    void readsIdentityFromCurrentWebUserSchema() {
        var body = """
                {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"200",
                "legacy":{"full_text":"web schema","created_at":"2018-10-10T20:19:24Z"},
                "core":{"user_results":{"result":{"__typename":"User","rest_id":"42",
                "core":{"screen_name":"alice","name":"Alice"},
                "avatar":{"image_url":"https://pbs.twimg.com/profile_images/alice.jpg"},
                "verification":{"verified":true}}}}}}}}
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.author().id()).isEqualTo("42");
        assertThat(post.author().username()).isEqualTo("alice");
        assertThat(post.author().displayName()).isEqualTo("Alice");
        assertThat(post.author().avatarUrl()).endsWith("alice.jpg");
        assertThat(post.author().verified()).isTrue();
    }

    @Test
    void readsPretranslationFromVisibilityResultWrapper() {
        var body = """
                {"result":{"__typename":"TweetWithVisibilityResults",
                "grok_translated_post_with_availability":{"is_available":true,"data":{
                  "translation":"ラッパーの事前翻訳","source_language":"en",
                  "destination_language":"ja"}},"tweet":{"__typename":"Tweet","rest_id":"204",
                  "legacy":{"full_text":"wrapper original","lang":"en",
                  "created_at":"2018-10-10T20:19:24Z"}}}}
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.preTranslated()).isNotNull();
        assertThat(post.preTranslated().text()).isEqualTo("ラッパーの事前翻訳");
        assertThat(post.preTranslated().targetLanguage()).isEqualTo("ja");
    }

    @Test
    void excludesPromotedTimelineEntries() {
        var body = """
                {"entries":[
                  {"entryId":"promoted-tweet-1","content":{"promotedMetadata":{"advertiser_id":"1"},
                    "itemContent":{"tweet_results":{"result":{"__typename":"Tweet","rest_id":"300",
                      "legacy":{"full_text":"sponsored","created_at":"2018-10-10T20:19:24Z"}}}}}},
                  {"entryId":"tweet-301","content":{"itemContent":{"tweet_results":{"result":{
                    "__typename":"Tweet","rest_id":"301",
                    "legacy":{"full_text":"organic","created_at":"2018-10-10T20:19:24Z"}}}}}}
                ]}
                """;

        var page = parser.parse(body);

        assertThat(page.posts()).singleElement().satisfies(post -> {
            assertThat(post.id()).isEqualTo("301");
            assertThat(post.text()).isEqualTo("organic");
        });
    }

    @Test
    void readsIdentityFromBookmarksUserWrapper() {
        var body = """
                {"data":{"bookmark_timeline_v2":{"timeline":{"instructions":[{"entries":[{
                "content":{"itemContent":{"tweet_results":{"result":{"__typename":"Tweet",
                "rest_id":"201","legacy":{"full_text":"saved post",
                "created_at":"2018-10-10T20:19:24Z"},"core":{"user_results":{"result":
                {"result":{"__typename":"User","rest_id":"43","core":{"screen_name":"bob",
                "name":"Bob"},"avatar":{"image_url":"https://pbs.twimg.com/bob.jpg"}}}}}}}}}}]}
                ]}}}}
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.author().id()).isEqualTo("43");
        assertThat(post.author().username()).isEqualTo("bob");
        assertThat(post.author().displayName()).isEqualTo("Bob");
        assertThat(post.author().avatarUrl()).isEqualTo("https://pbs.twimg.com/bob.jpg");
    }

    @Test
    void readsIdentityFromBookmarksSingularUserResult() {
        var body = """
                {"data":{"bookmark_timeline_v2":{"timeline":{"instructions":[{"entries":[{
                "content":{"itemContent":{"tweet_results":{"result":{"__typename":"Tweet",
                "rest_id":"202","legacy":{"full_text":"another saved post",
                "created_at":"2018-10-10T20:19:24Z"},"core":{"user_result":{"result":
                {"__typename":"User","rest_id":"44","legacy":{"screen_name":"carol",
                "name":"Carol","profile_image_url_https":"https://pbs.twimg.com/carol.jpg"}}}}}}}}}
                ]}]}}}}
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.author().id()).isEqualTo("44");
        assertThat(post.author().username()).isEqualTo("carol");
        assertThat(post.author().displayName()).isEqualTo("Carol");
        assertThat(post.author().avatarUrl()).isEqualTo("https://pbs.twimg.com/carol.jpg");
    }

    @Test
    void limitsBookmarksFallbackToTheTweetCoreAuthorArea() {
        var body = """
                {"data":{"bookmark_timeline_v2":{"tweet_results":{"result":{"__typename":"Tweet",
                "rest_id":"203","legacy":{"full_text":"saved schema variant",
                "created_at":"2018-10-10T20:19:24Z"},"core":{"account_reference":{"value":
                {"__typename":"User","rest_id":"45","core":{"screen_name":"dave","name":"Dave"},
                "avatar":{"image_url":"https://pbs.twimg.com/dave.jpg"}}}},"quoted_status_result":
                {"result":{"__typename":"Tweet","rest_id":"999","legacy":{"full_text":"quoted"},
                "core":{"user_results":{"result":{"__typename":"User","rest_id":"99","core":
                {"screen_name":"wrong","name":"Wrong"}}}}}}}}}}}
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.author().id()).isEqualTo("45");
        assertThat(post.author().username()).isEqualTo("dave");
        assertThat(post.author().displayName()).isEqualTo("Dave");
        assertThat(post.author().avatarUrl()).isEqualTo("https://pbs.twimg.com/dave.jpg");
    }

    @Test
    void rendersTheRetweetedSourceAsThePostAndKeepsOnlyTheReposterContext() {
        var body = """
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
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.id()).isEqualTo("250");
        assertThat(post.text()).isEqualTo("source text");
        assertThat(post.author().username()).isEqualTo("origin");
        assertThat(post.repostedBy().username()).isEqualTo("reposter");
        assertThat(post.likeCount()).isEqualTo(8);
        assertThat(post.repostCount()).isEqualTo(4);
        assertThat(post.viewCount()).isEqualTo(99);
    }

    @Test
    void usesTheCompleteNoteTweetBodyForARetweetedSource() {
        var body = """
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
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.id()).isEqualTo("251");
        assertThat(post.text())
                .isEqualTo("complete retweeted source body without an omitted ending");
        assertThat(post.author().username()).isEqualTo("origin");
        assertThat(post.repostedBy().username()).isEqualTo("reposter");
    }

    @Test
    void normalizesAQuotedTweetForTheEmbeddedWebStyleCard() {
        var body = """
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
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.text()).isEqualTo("my comment");
        assertThat(post.quotedPostId()).isEqualTo("399");
        assertThat(post.quotedPost().author().username()).isEqualTo("quoted");
        assertThat(post.quotedPost().text()).isEqualTo("quoted text");
        assertThat(post.quotedPost().preTranslated()).isNotNull();
        assertThat(post.quotedPost().preTranslated().text()).isEqualTo("quoted translation");
        assertThat(post.quotedPost().preTranslated().targetLanguage()).isEqualTo("en");
        assertThat(post.quotedPost().media()).hasSize(1);
    }

    @Test
    void usesCompleteNoteTweetBodiesForAQuoteAndItsEmbeddedSource() {
        var body = """
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
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.text()).isEqualTo("complete quote comment without an omitted ending");
        assertThat(post.quotedPost()).isNotNull();
        assertThat(post.quotedPost().text())
                .isEqualTo("complete quoted source body without an omitted ending");
    }

    @Test
    void preservesNativeReplyOrderAndConversationQualityClassification() {
        var body = """
                {"data":{"threaded_conversation_with_injections_v2":{"instructions":[{
                "type":"TimelineAddEntries","entries":[{"entryId":"conversationthread-1",
                "content":{"entryType":"TimelineTimelineModule","displayType":
                "VerticalConversation","clientEventInfo":{"details":{"conversationDetails":{
                "conversationSection":"LowQuality"}}},"items":[{"entryId":"reply-1",
                "item":{"itemContent":{"tweet_results":{"result":{"__typename":"Tweet",
                "rest_id":"701","legacy":{"full_text":"first native reply",
                "created_at":"2019-01-01T00:00:00Z"}}}},"clientEventInfo":{"details":{
                "conversationDetails":{"conversationSection":"LowQuality"}}}}}]}},
                {"entryId":"conversationthread-2","content":{"entryType":
                "TimelineTimelineModule","displayType":"VerticalConversation",
                "clientEventInfo":{"details":{"conversationDetails":{"conversationSection":
                "HighQuality"}}},"items":[{"entryId":"reply-2","item":{"itemContent":{
                "tweet_results":{"result":{"__typename":"Tweet","rest_id":"702","legacy":{
                "full_text":"second native reply","created_at":"2020-01-01T00:00:00Z"}}}}}}]}}
                ]}]}}}
                """;

        var page = parser.parseInResponseOrder(body);

        assertThat(page.posts()).extracting(TimelinePage.Post::id)
                .containsExactly("701", "702");
        assertThat(page.posts().get(0).conversationSection()).isEqualTo("LowQuality");
        assertThat(page.posts().get(1).conversationSection()).isEqualTo("HighQuality");
    }

    @Test
    void preservesInitialEngagementStateAndCommunityNoteDetails() {
        var body = """
                {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"500",
                "legacy":{"full_text":"post with context","lang":"en",
                "created_at":"2019-01-02T00:00:00Z","favorited":true,"retweeted":true},
                "birdwatch_pivot":{"title":{"text":"Community Note"},
                "note":{"data_v1":{"summary":{"text":"This image was taken in 2024."}}},
                "footer":{"text":"Rated helpful by readers"}}}}}}
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.liked()).isTrue();
        assertThat(post.reposted()).isTrue();
        assertThat(post.communityNote()).isNotNull();
        assertThat(post.communityNote().title()).isEqualTo("Community Note");
        assertThat(post.communityNote().text()).isEqualTo("This image was taken in 2024.");
        assertThat(post.communityNote().footer()).isEqualTo("Rated helpful by readers");
    }

    @Test
    void omitsMediaRedirectUrlsFromPostAndPretranslatedText() {
        var body = """
                {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"600",
                "legacy":{"full_text":"画像付きポスト https://t.co/media123","lang":"ja",
                "created_at":"2019-01-02T00:00:00Z","extended_entities":{"media":[{
                "id_str":"photo-600","type":"photo","url":"https://t.co/media123",
                "media_url_https":"https://pbs.twimg.com/media/photo600.jpg"}]}},
                "grok_translated_post_with_availability":{"is_available":true,"data":{
                "translation":"Post with image https://t.co/media123","source_language":"ja",
                "destination_language":"en"}}}}}}
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.text()).isEqualTo("画像付きポスト");
        assertThat(post.preTranslated().text()).isEqualTo("Post with image");
        assertThat(post.media()).singleElement().satisfies(media ->
                assertThat(media.url()).isEqualTo("https://pbs.twimg.com/media/photo600.jpg"));
    }

    @Test
    void normalizesAnXArticleAndOmitsItsRedirectUrl() {
        var body = """
                {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"700",
                "legacy":{"full_text":"https://t.co/article700","lang":"ja",
                "created_at":"2019-01-02T00:00:00Z","entities":{"urls":[{
                "url":"https://t.co/article700",
                "expanded_url":"https://x.com/i/article/701"}]}},"article":{"article_results":{
                "result":{"rest_id":"701","title":"NyTweetDeckの記事",
                "preview_text":"記事の概要です。","plain_text":"最初の段落。\\n\\n全文の続き。",
                "cover_media":{"media_info":{"original_img_url":
                "https://pbs.twimg.com/media/article-cover.jpg"}}}}}}}}}
                """;

        var post = parser.parse(body).posts().get(0);

        assertThat(post.text()).isEmpty();
        assertThat(post.article()).isNotNull();
        assertThat(post.article().id()).isEqualTo("701");
        assertThat(post.article().title()).isEqualTo("NyTweetDeckの記事");
        assertThat(post.article().previewText()).isEqualTo("記事の概要です。");
        assertThat(post.article().body()).isEqualTo("最初の段落。\n\n全文の続き。");
        assertThat(post.article().coverImageUrl())
                .isEqualTo("https://pbs.twimg.com/media/article-cover.jpg");
        assertThat(post.article().url()).isEqualTo("https://x.com/i/article/701");
    }

    @Test
    void expandsOrdinaryTcoLinksInPostNoteTweetAndPretranslation() {
        var body = """
                {"result":{"__typename":"Tweet","rest_id":"800",
                  "legacy":{"full_text":"本文 https://t.co/main","lang":"ja",
                    "created_at":"2019-01-02T00:00:00Z","entities":{"urls":[{
                      "url":"https://t.co/main","display_url":"example.com/readable",
                      "expanded_url":"https://example.com/expanded",
                      "unwound_url":"https://example.com/original?from=x"}]}},
                  "grok_translated_post_with_availability":{"is_available":true,"data":{
                    "translation":"Body https://t.co/main","source_language":"ja",
                    "destination_language":"en"}}}}
                """;
        var noteBody = """
                {"result":{"__typename":"Tweet","rest_id":"799",
                  "legacy":{"full_text":"short","created_at":"2019-01-01T00:00:00Z"},
                  "note_tweet":{"note_tweet_results":{"result":{
                    "text":"長文 https://t.co/note","entity_set":{"urls":[{
                      "url":"https://t.co/note",
                      "expanded_url":"https://docs.example.org/guide"}]}
                  }}}}}
                """;

        var post = parser.parse(body).posts().get(0);
        var notePost = parser.parse(noteBody).posts().get(0);

        assertThat(post.text()).isEqualTo("本文 https://example.com/original?from=x");
        assertThat(post.links()).singleElement().satisfies(link -> {
            assertThat(link.url()).isEqualTo("https://example.com/original?from=x");
            assertThat(link.displayText()).isEqualTo("example.com/readable");
        });
        assertThat(post.preTranslated().text())
                .isEqualTo("Body https://example.com/original?from=x");
        assertThat(notePost.text()).isEqualTo("長文 https://docs.example.org/guide");
    }
}
