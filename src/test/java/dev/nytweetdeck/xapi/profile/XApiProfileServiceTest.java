package dev.nytweetdeck.xapi.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

class XApiProfileServiceTest {

    private final XApiProfileService service =
            new XApiProfileService(JsonMapper.builder().build());

    @Test
    void loadsVersionedApiProfile() {
        var profile = service.profile();

        assertThat(profile.packageName()).isEqualTo("x-web");
        assertThat(profile.versionName()).isEqualTo("main.941731a8bedadd89a.js");
        assertThat(profile.standardHeaders())
                .containsEntry("X-Twitter-Client", "TwitterWebClient")
                .containsEntry("X-Twitter-API-Version", "5");
        assertThat(profile.standardHeaders().keySet())
                .noneMatch(name -> name.equalsIgnoreCase("Authorization"));
        assertThat(profile.restEndpoints())
                .containsEntry("livePipelineEvents", "/live_pipeline/events")
                .containsEntry(
                        "livePipelineUpdateSubscriptions",
                        "/1.1/live_pipeline/update_subscriptions");
    }

    @Test
    void resolvesVerifiedGraphQlOperationUrl() {
        var operation = service.requireOperation("homeForYou");

        assertThat(operation.key()).isEqualTo("home_timeline");
        assertThat(operation.resolveAgainst(service.profile().graphqlBaseUri()).toString())
                .isEqualTo("https://x.com/i/api/graphql/og4a4SdSF3WiQkkwaPCdPg/HomeTimeline");
    }

    @Test
    void includesTheCurrentBirdwatchNoteDetailOperation() {
        var operation = service.requireOperation("communityNote");

        assertThat(operation.operationName()).isEqualTo("BirdwatchFetchOneNote");
        assertThat(operation.operationId()).isEqualTo("1lt6XSRik4s93WG0BrEvig");
        assertThat(operation.featureKeys())
                .contains("responsive_web_birdwatch_media_notes_enabled")
                .contains("responsive_web_birdwatch_url_notes_enabled");
        assertThat(operation.fieldToggles())
                .containsExactly("withPayments", "withAuxiliaryUserLabels");
    }

    @Test
    void usesTheCurrentProfileRepliesTimelineOperation() {
        var operation = service.requireOperation("userReplies");

        assertThat(operation.operationName()).isEqualTo("UserRepliesTimeline");
        assertThat(operation.operationId()).isEqualTo("xz348nziCm96wndJ1S0MUQ");
    }

    @Test
    void rejectsUnknownOperationPurpose() {
        assertThatThrownBy(() -> service.requireOperation("not-defined"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-defined");
    }

    @Test
    void keepsVerifiedOperationsMissingFromRefreshedAssets() {
        var listBefore = service.requireOperation("list");
        var refreshed = new XWebMetadataResolver.ResolvedMetadata(
                "main.changed.js",
                Map.of(
                        "HomeTimeline",
                        new XWebMetadataResolver.ResolvedOperation(
                                "new-home-id",
                                "HomeTimeline",
                                XApiProfile.OperationType.QUERY,
                                List.of("feature_a"),
                                List.of())),
                List.of("feature_a"),
                Map.of("feature_a", true),
                List.of("ListLatestTweetsTimeline"));

        var updated = service.applyResolved(refreshed);

        assertThat(updated).isEqualTo(1);
        assertThat(service.requireOperation("homeForYou").operationId()).isEqualTo("new-home-id");
        assertThat(service.requireOperation("list")).isEqualTo(listBefore);
    }
}
