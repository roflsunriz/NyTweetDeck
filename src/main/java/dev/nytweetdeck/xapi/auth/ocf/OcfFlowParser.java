package dev.nytweetdeck.xapi.auth.ocf;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OcfFlowParser {

    private static final Map<String, OcfSubtaskType> TYPES = createTypes();

    private final ObjectMapper objectMapper;

    public OcfFlowParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OcfFlow parse(String body) {
        try {
            var root = objectMapper.readTree(body);
            var flowToken = requiredText(root, "flow_token");
            var subtasksNode = root.get("subtasks");
            if (subtasksNode == null || !subtasksNode.isArray()) {
                throw new IllegalArgumentException("OCF応答にsubtasks配列がありません。");
            }
            var subtasks = new ArrayList<OcfSubtask>();
            for (JsonNode subtaskNode : subtasksNode) {
                subtasks.add(parseSubtask(subtaskNode));
            }
            return new OcfFlow(flowToken, subtasks, parseAccount(subtasksNode));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new XApiHttpException("OCF応答を解析できません。", exception);
        }
    }

    private static OcfFlow.OcfAccount parseAccount(JsonNode subtasksNode) {
        for (var subtask : subtasksNode) {
            var openAccount = subtask.get("open_account");
            if (openAccount == null || !openAccount.isObject()) {
                continue;
            }
            var user = openAccount.get("user");
            var userId = firstNonNull(text(user, "id_str"), text(user, "id"));
            var username = text(user, "screen_name");
            var oauthToken = text(openAccount, "oauth_token");
            var oauthTokenSecret = text(openAccount, "oauth_token_secret");
            if (userId != null
                    && username != null
                    && oauthToken != null
                    && oauthTokenSecret != null) {
                return new OcfFlow.OcfAccount(
                        userId, username, text(user, "name"), oauthToken, oauthTokenSecret);
            }
        }
        return null;
    }

    private OcfSubtask parseSubtask(JsonNode subtaskNode) {
        var id = requiredText(subtaskNode, "subtask_id");
        String payloadName = null;
        OcfSubtaskType type = OcfSubtaskType.UNSUPPORTED;
        for (Map.Entry<String, OcfSubtaskType> candidate : TYPES.entrySet()) {
            if (subtaskNode.get(candidate.getKey()) != null) {
                payloadName = candidate.getKey();
                type = candidate.getValue();
                break;
            }
        }
        var payload = payloadName == null ? subtaskNode : subtaskNode.get(payloadName);
        var prompt = firstText(payload, "primary_text", "text");
        if (prompt == null) {
            prompt = firstText(payload, "header", "primary_text", "text");
        }
        var hint = text(payload, "hint_text");
        if (hint == null) {
            hint = firstText(payload, "secondary_text", "text");
        }
        var nextLink = findNextLink(payload);
        return new OcfSubtask(id, type, prompt, hint, nextLink, parseChoices(payload));
    }

    private static List<OcfSubtask.Choice> parseChoices(JsonNode payload) {
        var choicesNode = payload == null ? null : payload.get("choices");
        if (choicesNode == null || !choicesNode.isArray()) {
            return List.of();
        }
        var choices = new ArrayList<OcfSubtask.Choice>();
        for (JsonNode choice : choicesNode) {
            var id = text(choice, "id");
            if (id == null) {
                id = text(choice, "value");
            }
            var label = firstText(choice, "text", "text");
            if (label == null) {
                label = text(choice, "label");
            }
            if (id != null) {
                choices.add(new OcfSubtask.Choice(id, label == null ? id : label));
            }
        }
        return choices;
    }

    private static String findNextLink(JsonNode payload) {
        if (payload == null) {
            return "next_link";
        }
        var direct = payload.get("next_link");
        if (direct != null) {
            var linkId = text(direct, "link_id");
            if (linkId != null) {
                return linkId;
            }
        }
        var links = payload.get("navigation_links");
        if (links != null && links.isArray()) {
            for (JsonNode link : links) {
                var linkId = text(link, "link_id");
                if (linkId != null) {
                    return linkId;
                }
            }
        }
        return "next_link";
    }

    private static String requiredText(JsonNode node, String field) {
        var value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "が空です。");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    private static String firstText(JsonNode node, String... path) {
        var current = node;
        for (String field : path) {
            if (current == null) {
                return null;
            }
            current = current.get(field);
        }
        return current == null || current.isNull() ? null : current.asString(null);
    }

    private static String firstNonNull(String first, String second) {
        return first == null ? second : first;
    }

    private static Map<String, OcfSubtaskType> createTypes() {
        var types = new LinkedHashMap<String, OcfSubtaskType>();
        types.put("enter_text", OcfSubtaskType.TEXT);
        types.put("enter_username", OcfSubtaskType.USERNAME);
        types.put("enter_password", OcfSubtaskType.PASSWORD);
        types.put("choice_selection", OcfSubtaskType.CHOICE);
        types.put("email_verification", OcfSubtaskType.EMAIL_CODE);
        types.put("phone_verification", OcfSubtaskType.PHONE_CODE);
        types.put("check_logged_in_account", OcfSubtaskType.CONFIRM);
        types.put("js_instrumentation", OcfSubtaskType.JS_INSTRUMENTATION);
        types.put("open_account", OcfSubtaskType.COMPLETE);
        types.put("security_key", OcfSubtaskType.SECURITY_KEY);
        types.put("passkey", OcfSubtaskType.PASSKEY);
        types.put("app_attestation", OcfSubtaskType.APP_ATTESTATION);
        return Map.copyOf(types);
    }
}
