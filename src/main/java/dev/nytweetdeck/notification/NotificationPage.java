package dev.nytweetdeck.notification;

import dev.nytweetdeck.timeline.TimelinePage;
import java.util.List;

public record NotificationPage(
        List<Notification> notifications,
        List<TimelinePage.Post> posts,
        String nextCursor) {

    public NotificationPage {
        notifications = List.copyOf(notifications);
        posts = List.copyOf(posts);
    }

    public record Notification(
            String id,
            String kind,
            String text,
            String postId,
            List<String> imageUrls) {
        public Notification {
            imageUrls = List.copyOf(imageUrls);
        }
    }
}
