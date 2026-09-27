package dev.nytweetdeck.post;

import java.time.Instant;
import java.util.List;

/** Optional X Web composer controls shared by immediate and scheduled posts. */
public record PostComposition(
        String text,
        String inReplyToPostId,
        String quotePostId,
        List<String> mediaIds,
        Poll poll,
        Instant scheduledAt,
        Place place,
        boolean paidPartnership,
        boolean aiGenerated) {

    public PostComposition {
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
    }

    public record Poll(List<String> choices, int durationMinutes) {
        public Poll {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    public record Place(String id, String geoSearchRequestId) {}
}
