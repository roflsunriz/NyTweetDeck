package dev.nytweetdeck.notification;

import dev.nytweetdeck.timeline.TimelinePage;
import java.util.List;

public record CommunityNoteDetail(
        String noteId,
        String text,
        List<Source> sources,
        TimelinePage.Post post) {

    public CommunityNoteDetail {
        sources = List.copyOf(sources);
    }

    public record Source(int fromIndex, int toIndex, String url) {}
}
