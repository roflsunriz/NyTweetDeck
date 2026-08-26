package dev.nytweetdeck.xapi.live;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LivePipelineEventParser {

    private final ObjectMapper objectMapper;

    public LivePipelineEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PipelineEvent parse(String body) {
        try {
            var root = objectMapper.readTree(body);
            var topic = text(root, "topic");
            var payload = root.get("payload");
            if (topic == null || payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("Live Pipelineイベントにtopicまたはpayloadがありません。");
            }
            for (Map.Entry<String, JsonNode> property : payload.properties()) {
                var type = property.getKey();
                if (type.equals("tweet_engagement")
                        || type.equals("dm_update")
                        || type.equals("dm_typing")
                        || type.equals("live_content")) {
                    return new PipelineEvent(topic, type, entityId(topic), property.getValue());
                }
            }
            throw new IllegalArgumentException("未確認のLive Pipelineイベント種別です。");
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new XApiHttpException("Live Pipelineイベントを解析できません。", exception);
        }
    }

    private static String entityId(String topic) {
        var separator = topic.lastIndexOf('/');
        return separator >= 0 && separator < topic.length() - 1
                ? topic.substring(separator + 1)
                : null;
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    public record PipelineEvent(String topic, String type, String entityId, JsonNode payload) {}
}
