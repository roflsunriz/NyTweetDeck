package dev.nytweetdeck.xapi.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AndroidApiProfileServiceTest {

    private final AndroidApiProfileService service =
            new AndroidApiProfileService(JsonMapper.builder().build());

    @Test
    void loadsVersionedAndroidApiProfile() {
        var profile = service.profile();

        assertThat(profile.packageName()).isEqualTo("com.twitter.android");
        assertThat(profile.versionName()).isEqualTo("12.19.1-release.0");
        assertThat(profile.standardHeaders())
                .containsEntry("X-Twitter-Client", "TwitterAndroid")
                .containsEntry("X-Twitter-API-Version", "5");
        assertThat(profile.standardHeaders().keySet())
                .noneMatch(name -> name.equalsIgnoreCase("Authorization"));
    }

    @Test
    void resolvesVerifiedGraphQlOperationUrl() {
        var operation = service.requireOperation("homeForYou");

        assertThat(operation.key()).isEqualTo("home_timeline");
        assertThat(operation.resolveAgainst(service.profile().graphqlBaseUri()).toString())
                .isEqualTo("https://api.x.com/graphql/HHfhIGSiG31KoQG5mStocg/HomeTimeline");
    }

    @Test
    void rejectsUnknownOperationPurpose() {
        assertThatThrownBy(() -> service.requireOperation("not-defined"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-defined");
    }
}
