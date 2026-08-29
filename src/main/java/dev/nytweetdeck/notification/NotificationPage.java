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
            String noteId,
            String postId,
            List<Actor> actors,
            List<String> imageUrls) {
        public Notification {
            actors = List.copyOf(actors);
            imageUrls = List.copyOf(imageUrls);
        }
    }

    public record Actor(
            String id, String username, String displayName, String avatarUrl) {}
}
