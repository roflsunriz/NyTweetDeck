package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient;
import dev.nytweetdeck.xapi.profile.XApiProfileService;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PostTranslationServiceTest {

    @Test
    void translatesThroughTheCurrentXWebStratoEndpointAndCachesTheResult() {
        var requestedPostId = new AtomicReference<String>();
        var requestedLanguage = new AtomicReference<String>();
        var calls = new int[1];
        var restClient = new AuthenticatedRestClient(null, null, null) {
            @Override
            public RestResult get(
                    String accountId,
                    String endpointName,
                    Map<String, String> pathVariables,
                    Map<String, String> parameters,
                    String language) {
                calls[0]++;
                assertThat(endpointName).isEqualTo("translatePost");
                requestedPostId.set(pathVariables.get("postId"));
                assertThat(pathVariables).containsEntry("translationSource", "Google");
                requestedLanguage.set(language);
                return new RestResult(
                        endpointName,
                        "{\"id_str\":\"123\",\"translation\":\"こんにちは世界\"}");
            }
        };
        var mapper = JsonMapper.builder().build();
        var profileService = new XApiProfileService(mapper) {
            @Override
            public boolean featureEnabled(String key) {
                return false;
            }
        };
        var service = new PostTranslationService(restClient, mapper, profileService);

        var first = service.translate("account-1", "123", "en", "ja");
        var second = service.translate("account-1", "123", "en", "ja");

        assertThat(first.text()).isEqualTo("こんにちは世界");
        assertThat(first.provider()).isEqualTo("Google");
        assertThat(second).isSameAs(first);
        assertThat(calls[0]).isEqualTo(1);
        assertThat(requestedPostId.get()).isEqualTo("123");
        assertThat(requestedLanguage.get()).isEqualTo("ja");
    }

    @Test
    void rejectsEqualOrInvalidLanguagesBeforeCommunication() {
        var service = new PostTranslationService(
                null, JsonMapper.builder().build(), null);

        assertThatThrownBy(() -> service.translate("account-1", "123", "ja-JP", "ja"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同じ");
        assertThatThrownBy(() -> service.translate("account-1", "123", "unknown", "ja"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("形式");
    }
}
