package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import dev.nytweetdeck.timeline.TimelineResponseParser;
import dev.nytweetdeck.timeline.TimelinePage;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PostServiceTest {

    @Test
    void validatesPostIdsAndPostLengthBeforeNetwork() {
        assertThatThrownBy(() -> PostService.validatePostId("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("形式");
        assertThatThrownBy(() -> new PostService(null, null, null).create("account", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1〜4000");
        assertThatThrownBy(() -> new PostService(null, null, null).create(
                        "account", "a".repeat(4001), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1〜4000");
        assertThatThrownBy(() -> new PostService(null, null, null)
                        .create("account", "quote", null, "invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("形式");
    }

    @Test
    void buildsVerifiedWebQuoteAttachmentUrl() {
        var variables = new PostService(null, null, null)
                .createVariables(" quote ", null, "123456789");

        assertThat(variables)
                .containsEntry("tweet_text", "quote")
                .containsEntry("attachment_url", "https://twitter.com/i/status/123456789");
    }

    @Test
    void mapsOnlyTheNativeXReplyRankingModes() {
        assertThat(PostService.rankingMode("relevance")).isEqualTo("Relevance");
        assertThat(PostService.rankingMode("RECENCY")).isEqualTo("Recency");
        assertThat(PostService.rankingMode(" likes ")).isEqualTo("Likes");
        assertThatThrownBy(() -> PostService.rankingMode("popular"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("並び順");
    }

    @Test
    void sendsTheSelectedNativeRankingModeToTheConversationRequest() {
        var focal = """
                {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"123",
                "legacy":{"full_text":"focal post","created_at":"2026-08-28T00:00:00Z"}}}}}
                """;
        var conversation = """
                {"data":{"threaded_conversation_with_injections_v2":{"instructions":[{
                "entries":[{"content":{"itemContent":{"tweet_results":{"result":{
                "__typename":"Tweet","rest_id":"124","legacy":{"full_text":"reply",
                "created_at":"2026-08-28T00:01:00Z","in_reply_to_status_id_str":"123"}}}}}}]}]}}}
                """;
        var client = new RecordingGraphQlClient(focal, conversation);
        var service = new PostService(
                client,
                new TimelineResponseParser(JsonMapper.builder().build()),
                null);

        var detail = service.detail("account", "123", null, "ja", "likes");

        assertThat(detail.replies()).extracting(reply -> reply.id()).containsExactly("124");
        assertThat(client.conversationVariables).containsEntry("rankingMode", "Likes");
    }

    @Test
    void loadsReplyAncestorsAsContextWhenAReplyIsOpenedFromATimeline() {
        var focal = """
                {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"124",
                "legacy":{"full_text":"reply opened from timeline","in_reply_to_status_id_str":"100"}}}}}
                """;
        var replyConversation = """
                {"data":{"threaded_conversation_with_injections_v2":{"instructions":[{
                "entries":[{"content":{"itemContent":{"tweet_results":{"result":{
                "__typename":"Tweet","rest_id":"124","legacy":{"full_text":"reply opened from timeline",
                "in_reply_to_status_id_str":"100"}}}}}}]}]}}}
                """;
        var parentConversation = """
                {"data":{"threaded_conversation_with_injections_v2":{"instructions":[{
                "entries":[
                {"content":{"itemContent":{"tweet_results":{"result":{
                "__typename":"Tweet","rest_id":"99","legacy":{"full_text":"root"}}}}}},
                {"content":{"itemContent":{"tweet_results":{"result":{
                "__typename":"Tweet","rest_id":"100","legacy":{"full_text":"previous reply",
                "in_reply_to_status_id_str":"99"}}}}}}
                ]}]}}}
                """;
        var client = new RecordingGraphQlClient(focal, replyConversation, parentConversation);

        var detail = new PostService(
                client,
                new TimelineResponseParser(JsonMapper.builder().build()),
                null).detail("account", "124", null, "ja", "relevance");

        assertThat(detail.contextPosts()).extracting(TimelinePage.Post::id)
                .containsExactly("99", "100");
        assertThat(detail.replies()).extracting(TimelinePage.Post::id)
                .doesNotContain("99", "100");
        assertThat(client.conversationVariables).containsEntry("focalTweetId", "100");
    }

    @Test
    void keepsRecommendationsOutOfRepliesAndAncestorContextOnEveryPage() {
        var focal = "{\"__typename\":\"Tweet\",\"rest_id\":\"124\",\"legacy\":{\"full_text\":\"focal\",\"in_reply_to_status_id_str\":\"100\"}}";
        var recommendations = "{\"entryId\":\"tweetdetailrelatedtweets-1\",\"items\":[{\"__typename\":\"Tweet\",\"rest_id\":\"100\",\"legacy\":{\"full_text\":\"recommended ancestor ID\"}}]}";
        var client = new RecordingGraphQlClient(focal, recommendations, recommendations);
        var service = new PostService(client, new TimelineResponseParser(JsonMapper.builder().build()), null);
        for (var cursor : new String[] {"", "next"}) {
            var detail = service.detail("account", "124", cursor);
            assertThat(detail.contextPosts()).isEmpty();
            assertThat(detail.replies()).isEmpty();
            assertThat(detail.relatedPosts()).extracting(TimelinePage.Post::id).containsExactly("100");
        }
    }

    private static final class RecordingGraphQlClient extends AuthenticatedGraphQlClient {
        private final String focal;
        private final String conversation;
        private final String parentConversation;
        private Map<String, Object> conversationVariables = Map.of();

        private RecordingGraphQlClient(String focal, String conversation) {
            this(focal, conversation, conversation);
        }

        private RecordingGraphQlClient(
                String focal, String conversation, String parentConversation) {
            super(null, null, null, null);
            this.focal = focal;
            this.conversation = conversation;
            this.parentConversation = parentConversation;
        }

        @Override
        public GraphQlResult execute(
                String accountId,
                String purpose,
                Map<String, Object> variables,
                String language) {
            if ("postDetail".equals(purpose)) {
                return new GraphQlResult(purpose, "TweetResultByRestId", focal);
            }
            if ("conversation".equals(purpose)) {
                conversationVariables = Map.copyOf(variables);
                var focalTweetId = variables.get("focalTweetId");
                return new GraphQlResult(
                        purpose,
                        "TweetDetail",
                        "100".equals(focalTweetId) ? parentConversation : conversation);
            }
            throw new IllegalArgumentException("unexpected purpose: " + purpose);
        }
    }
}
