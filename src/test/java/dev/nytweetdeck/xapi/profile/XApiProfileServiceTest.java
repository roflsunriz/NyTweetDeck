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
        assertThat(profile.versionName()).isEqualTo("current");
        assertThat(profile.standardHeaders())
                .containsEntry("X-Twitter-Client", "TwitterWebClient")
                .containsEntry("X-Twitter-API-Version", "5");
        assertThat(profile.standardHeaders().keySet())
                .noneMatch(name -> name.equalsIgnoreCase("Authorization"));
        assertThat(service.featureEnabled("responsive_web_x_translation_enabled"))
                .isFalse();
    }

    @Test
    void resolvesVerifiedGraphQlOperationUrl() {
        var operation = service.requireOperation("homeForYou");

        assertThat(operation.key()).isEqualTo("home_timeline");
        assertThat(operation.resolveAgainst(service.profile().graphqlBaseUri()).toString())
                .isEqualTo("https://api.x.com/graphql/wp06oo3fRGU4P1sK8rECqQ/HomeTimeline");
    }

    @Test
    void rejectsUnknownOperationPurpose() {
        assertThatThrownBy(() -> service.requireOperation("not-defined"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-defined");
    }

    @Test
    void keepsTheVerifiedSnapshotWhenARefreshIsIncomplete() {
        var before = service.profile();
        var incomplete = new XWebMetadataResolver.ResolvedMetadata(
                "main.changed.js", Map.of(), List.of(), Map.of());

        assertThatThrownBy(() -> service.applyResolved(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必須X Web operation");
        assertThat(service.profile()).isSameAs(before);
    }
}
