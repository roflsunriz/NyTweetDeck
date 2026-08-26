package dev.nytweetdeck.user;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nytweetdeck.timeline.TimelineResponseParser;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class UserProfileServiceTest {

    @Test
    void normalizesCurrentWebProfileAndMutualFollowers() {
        var mapper = JsonMapper.builder().build();
        var service = new UserProfileService(
                fakeClient(new AtomicReference<>()), new TimelineResponseParser(mapper), mapper);

        var profile = service.profile("account", "42");

        assertThat(profile.username()).isEqualTo("alice");
        assertThat(profile.displayName()).isEqualTo("Alice");
        assertThat(profile.description()).isEqualTo("Profile description");
        assertThat(profile.location()).isEqualTo("Tokyo");
        assertThat(profile.followingCount()).isEqualTo(10);
        assertThat(profile.followerCount()).isEqualTo(20);
        assertThat(profile.mutualFollowerCount()).isEqualTo(1);
        assertThat(profile.mutualFollowers()).extracting(UserProfilePage.RelatedUser::username)
                .containsExactly("bob");
    }

    @Test
    void mapsProfileTabsToDedicatedWebOperations() {
        var mapper = JsonMapper.builder().build();
        var purpose = new AtomicReference<String>();
        var service = new UserProfileService(
                fakeClient(purpose), new TimelineResponseParser(mapper), mapper);

        service.timeline("account", "42", "highlights", null);
        assertThat(purpose.get()).isEqualTo("userHighlights");
        service.timeline("account", "42", "media", null);
        assertThat(purpose.get()).isEqualTo("userMedia");
    }

    private static AuthenticatedGraphQlClient fakeClient(AtomicReference<String> purpose) {
        return new AuthenticatedGraphQlClient(null, null, null, null) {
            @Override
            public GraphQlResult execute(
                    String accountId, String operationPurpose, Map<String, Object> variables) {
                purpose.set(operationPurpose);
                if (operationPurpose.equals("userByRestId")) {
                    return new GraphQlResult(operationPurpose, "UserByRestId", """
                            {"data":{"user":{"result":{"__typename":"User","rest_id":"42",
                            "core":{"screen_name":"alice","name":"Alice","created_at":"2020-01-02T00:00:00Z"},
                            "avatar":{"image_url":"https://pbs.twimg.com/alice.jpg"},
                            "profile_bio":{"description":"Profile description"},
                            "location":{"location":"Tokyo"},"website":{"url":"https://example.com"},
                            "relationship_counts":{"friends_count":10,"followers_count":20},
                            "relationship_perspectives":{"following":true,"followed_by":true},
                            "verification":{"verified":true}}}}}
                            """);
                }
                if (operationPurpose.equals("followersYouKnow")) {
                    return new GraphQlResult(operationPurpose, "FollowersYouKnow", """
                            {"data":{"user":{"result":{"timeline":{"timeline":{"instructions":[
                            {"entries":[{"content":{"itemContent":{"user_results":{"result":{
                            "__typename":"User","rest_id":"7","core":{"screen_name":"bob","name":"Bob"},
                            "avatar":{"image_url":"https://pbs.twimg.com/bob.jpg"}}}}}}]}]}}}}}}
                            """);
                }
                return new GraphQlResult(operationPurpose, operationPurpose, "{\"data\":{}}");
            }
        };
    }
}
