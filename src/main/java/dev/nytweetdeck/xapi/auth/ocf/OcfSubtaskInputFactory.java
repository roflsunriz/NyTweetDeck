package dev.nytweetdeck.xapi.auth.ocf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class OcfSubtaskInputFactory {

    private final ObjectMapper objectMapper;

    public OcfSubtaskInputFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String create(OcfFlow flow, OcfSubtask subtask, Submission submission) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("link", submission.link() == null ? subtask.nextLink() : submission.link());
        switch (subtask.type()) {
            case TEXT -> payload.put("text", requireValue(submission.value(), "text"));
            case USERNAME -> payload.put("username", requireValue(submission.value(), "username"));
            case PASSWORD -> payload.put("password", requireValue(submission.value(), "password"));
            case EMAIL_CODE -> payload.put("email_verification_code", requireValue(submission.value(), "email code"));
            case PHONE_CODE -> payload.put("verification_code", requireValue(submission.value(), "phone code"));
            case CHOICE -> {
                var choices = submission.choiceIds();
                if (choices.isEmpty()) {
                    throw new IllegalArgumentException("選択肢が空です。");
                }
                payload.put("selected_choices", choices);
                payload.put("primary_choice", choices.get(0));
            }
            case CONFIRM, COMPLETE -> {
                // These subtasks only submit the navigation link.
            }
            default -> throw new IllegalArgumentException(
                    "自動送信できないOCF subtaskです: " + subtask.type());
        }

        var subtaskInput = new LinkedHashMap<String, Object>();
        subtaskInput.put("subtask_id", subtask.id());
        subtaskInput.put(payloadName(subtask.type()), payload);
        var request = new LinkedHashMap<String, Object>();
        request.put("flow_token", flow.flowToken());
        request.put("subtask_inputs", List.of(subtaskInput));
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException exception) {
            throw new IllegalStateException("OCF入力をJSONへ変換できません。", exception);
        }
    }

    private static String payloadName(OcfSubtaskType type) {
        return switch (type) {
            case TEXT -> "enter_text";
            case USERNAME -> "enter_username";
            case PASSWORD -> "enter_password";
            case CHOICE -> "choice_selection";
            case EMAIL_CODE -> "email_verification";
            case PHONE_CODE -> "phone_verification";
            case CONFIRM -> "check_logged_in_account";
            case COMPLETE -> "open_account";
            default -> throw new IllegalArgumentException("未対応のOCF入力型です: " + type);
        };
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "が空です。");
        }
        return value;
    }

    public record Submission(String value, List<String> choiceIds, String link) {
        public Submission {
            choiceIds = choiceIds == null ? List.of() : List.copyOf(choiceIds);
        }
    }
}
