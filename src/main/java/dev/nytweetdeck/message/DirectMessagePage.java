package dev.nytweetdeck.message;

import java.util.List;

public record DirectMessagePage(List<DirectMessage> messages, String nextCursor) {

    public DirectMessagePage {
        messages = List.copyOf(messages);
    }

    public record DirectMessage(
            String id,
            String conversationId,
            String senderId,
            String senderName,
            String senderUsername,
            String senderAvatarUrl,
            String text,
            long timestamp) {}
}
