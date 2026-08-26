package dev.nytweetdeck.xapi.auth.ocf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nytweetdeck.xapi.auth.ocf.OcfSubtaskInputFactory.Submission;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OcfSubtaskInputFactoryTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OcfSubtaskInputFactory factory = new OcfSubtaskInputFactory(jsonMapper);

    @Test
    void createsPasswordSubmissionWithFlowAndLink() throws Exception {
        var flow = new OcfFlow("flow-token", List.of());
        var subtask = new OcfSubtask(
                "LoginEnterPassword",
                OcfSubtaskType.PASSWORD,
                "Password",
                null,
                "next_link",
                List.of());

        var json = factory.create(flow, subtask, new Submission("secret", List.of(), null));
        var tree = jsonMapper.readTree(json);

        assertThat(tree.get("flow_token").asString()).isEqualTo("flow-token");
        var input = tree.get("subtask_inputs").get(0);
        assertThat(input.get("subtask_id").asString()).isEqualTo("LoginEnterPassword");
        assertThat(input.get("enter_password").get("password").asString()).isEqualTo("secret");
        assertThat(input.get("enter_password").get("link").asString()).isEqualTo("next_link");
    }

    @Test
    void createsChoiceSubmission() throws Exception {
        var flow = new OcfFlow("flow-token", List.of());
        var subtask = new OcfSubtask(
                "LoginAcid", OcfSubtaskType.CHOICE, null, null, "next_link", List.of());

        var json = factory.create(
                flow, subtask, new Submission(null, List.of("email", "phone"), null));
        var payload = jsonMapper.readTree(json)
                .get("subtask_inputs")
                .get(0)
                .get("choice_selection");

        assertThat(payload.get("primary_choice").asString()).isEqualTo("email");
        assertThat(payload.get("selected_choices").size()).isEqualTo(2);
    }

    @Test
    void refusesUnsupportedSecurityStep() {
        var flow = new OcfFlow("flow-token", List.of());
        var subtask = new OcfSubtask(
                "SecurityKey", OcfSubtaskType.SECURITY_KEY, null, null, "next_link", List.of());

        assertThatThrownBy(() -> factory.create(
                        flow, subtask, new Submission("unsafe", List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自動送信できない");
    }
}
