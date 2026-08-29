package dev.nytweetdeck.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotificationResponseParserTest {

    @Test
    void parsesConfirmedWebTimelineNotificationAndSanitizesImages() {
        var parser = new NotificationResponseParser(JsonMapper.builder().build());
        var notifications = parser.parse("""
                {"entries":[{"content":{"notification":{"id":"follow-1","notification_icon":"person","url":{"url":"twitter://user?screen_name=alice","url_type":"DeepLink"},"socialContext":{"generalContext":{"text":"Alice followed you","contextImageUrls":["https://pbs.twimg.com/alice.jpg","javascript:bad"]}}}}}]}
                """);

        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.id()).isEqualTo("follow-1");
            assertThat(notification.kind()).isEqualTo("follow");
            assertThat(notification.text()).isEqualTo("Alice followed you");
            assertThat(notification.postId()).isNull();
            assertThat(notification.actors()).singleElement().satisfies(actor -> {
                assertThat(actor.id()).isNull();
                assertThat(actor.username()).isEqualTo("alice");
            });
            assertThat(notification.imageUrls()).containsExactly("https://pbs.twimg.com/alice.jpg");
        });
    }

    @Test
    void collectsEveryEmbeddedFollowerForAnAggregatedFollowNotification() {
        var parser = new NotificationResponseParser(JsonMapper.builder().build());

        var notification = parser.parse("""
                {"notification":{"id":"follow-many","notification_icon":"person",
                "message":{"text":"Alice and Bob followed you"},"template":{"actors":[
                {"user_results":{"result":{"__typename":"User","rest_id":"42",
                "core":{"screen_name":"alice","name":"Alice"},
                "avatar":{"image_url":"https://pbs.twimg.com/alice.jpg"}}}},
                {"user_results":{"result":{"__typename":"User","rest_id":"84",
                "core":{"screen_name":"bob","name":"Bob"},
                "avatar":{"image_url":"https://pbs.twimg.com/bob.jpg"}}}}]}}}
                """).get(0);

        assertThat(notification.kind()).isEqualTo("follow");
        assertThat(notification.actors()).extracting(NotificationPage.Actor::id)
                .containsExactly("42", "84");
        assertThat(notification.actors()).extracting(NotificationPage.Actor::username)
                .containsExactly("alice", "bob");
    }

    @Test
    void classifiesLikeAndLinksItsTargetPostInsideNyTweetDeck() {
        var parser = new NotificationResponseParser(JsonMapper.builder().build());

        var notification = parser.parse("""
                {"notification":{"id":"like-1","notification_icon":"heart",
                "message":{"text":"Alice liked your post"},
                "url":{"url":"https://x.com/alice/status/123"},
                "template":{"target":{"__typename":"Tweet","rest_id":"123"},
                "actor":{"profile_image_url_https":"https://pbs.twimg.com/alice.jpg"}}}}
                """).get(0);

        assertThat(notification.kind()).isEqualTo("like");
        assertThat(notification.postId()).isEqualTo("123");
        assertThat(notification.imageUrls()).containsExactly("https://pbs.twimg.com/alice.jpg");
    }

    @Test
    void linksACommunityNoteFromItsNestedWebDeepLink() {
        var parser = new NotificationResponseParser(JsonMapper.builder().build());

        var notification = parser.parse("""
                {"notification":{"id":"community-1","notification_icon":"birdwatch",
                "socialContext":{"generalContext":{"text":"Community Note added"}},
                "rich_message":{"text":"Readers added context to this post.","entities":[
                {"ref":{"type":"TimelineUrl","url":"twitter://tweet?id=987"}}]}}}
                """).get(0);

        assertThat(notification.kind()).isEqualTo("community_note");
        assertThat(notification.text()).isEqualTo("Community Note added");
        assertThat(notification.noteId()).isNull();
        assertThat(notification.postId()).isEqualTo("987");
    }

    @Test
    void extractsTheActualNoteBodyAndIdsFromCurrentTimelineNotification() {
        var parser = new NotificationResponseParser(JsonMapper.builder().build());

        var notification = parser.parse("""
                {"notification":{"id":"community-current","notification_icon":"birdwatch_note",
                "notification_social_context":{"text":"A Community Note was added"},
                "notification_url":{"url":"https://twitter.com/i/birdwatch/n/555?src=notification"},
                "rich_message":{"text":"A Community Note was added to a post you interacted with."},
                "template":{"additional_context":{"text":"This is the complete note body with a source.",
                "entities":[{"fromIndex":42,"toIndex":48,"ref":{"type":"TimelineUrl",
                "url":"https://x.com/alice/status/987"}}]}}}}
                """).get(0);

        assertThat(notification.kind()).isEqualTo("community_note");
        assertThat(notification.noteId()).isEqualTo("555");
        assertThat(notification.postId()).isEqualTo("987");
    }
}
