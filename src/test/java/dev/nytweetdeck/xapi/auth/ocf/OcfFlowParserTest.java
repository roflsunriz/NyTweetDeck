package dev.nytweetdeck.xapi.auth.ocf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OcfFlowParserTest {

    private final OcfFlowParser parser = new OcfFlowParser(JsonMapper.builder().build());

    @Test
    void parsesDynamicLoginSubtasksWithoutExposingFlowToken() {
        var body = """
                {
                  "flow_token": "secret-flow-token",
                  "subtasks": [
                    {
                      "subtask_id": "LoginEnterUserIdentifierSSO",
                      "enter_text": {
                        "primary_text": {"text": "電話番号、メールアドレス、またはユーザー名"},
                        "hint_text": "入力してください",
                        "next_link": {"link_id": "next_link"}
                      }
                    },
                    {
                      "subtask_id": "LoginEnterPassword",
                      "enter_password": {
                        "primary_text": {"text": "パスワードを入力"}
                      }
                    },
                    {
                      "subtask_id": "LoginAcid",
                      "choice_selection": {
                        "choices": [
                          {"id": "email", "label": "メール"},
                          {"id": "phone", "label": "電話"}
                        ]
                      }
                    }
                  ]
                }
                """;

        var flow = parser.parse(body);

        assertThat(flow.flowToken()).isEqualTo("secret-flow-token");
        assertThat(flow.toString()).doesNotContain("secret-flow-token");
        assertThat(flow.subtasks()).extracting(OcfSubtask::type)
                .containsExactly(OcfSubtaskType.TEXT, OcfSubtaskType.PASSWORD, OcfSubtaskType.CHOICE);
        assertThat(flow.subtasks().get(0).prompt()).contains("ユーザー名");
        assertThat(flow.subtasks().get(0).nextLink()).isEqualTo("next_link");
        assertThat(flow.subtasks().get(2).choices())
                .containsExactly(
                        new OcfSubtask.Choice("email", "メール"),
                        new OcfSubtask.Choice("phone", "電話"));
    }

    @Test
    void preservesUnknownSubtaskAsUnsupported() {
        var flow = parser.parse("""
                {"flow_token":"token","subtasks":[{"subtask_id":"FutureStep","future_input":{}}]}
                """);

        assertThat(flow.subtasks().get(0).type()).isEqualTo(OcfSubtaskType.UNSUPPORTED);
    }

    @Test
    void rejectsResponseWithoutFlowToken() {
        assertThatThrownBy(() -> parser.parse("{\"subtasks\":[]}"))
                .isInstanceOf(XApiHttpException.class)
                .hasMessageContaining("解析");
    }

    @Test
    void extractsCompletedAccountWithoutExposingOauthSecrets() {
        var flow = parser.parse("""
                {"flow_token":"token","subtasks":[{"subtask_id":"OpenAccount","open_account":{"user":{"id_str":"42","screen_name":"alice","name":"Alice"},"oauth_token":"oauth-value","oauth_token_secret":"oauth-secret"}}]}
                """);

        assertThat(flow.account().userId()).isEqualTo("42");
        assertThat(flow.account().username()).isEqualTo("alice");
        assertThat(flow.toString()).doesNotContain("oauth-value", "oauth-secret");
    }
}
