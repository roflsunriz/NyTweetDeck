package dev.nytweetdeck.notification;

import java.util.List;

public record CommunityNoteTranslation(String noteId, boolean available, String text,
        String sourceLanguage, String targetLanguage, String provider,
        List<CommunityNoteDetail.Source> sources) {
    public CommunityNoteTranslation {
        sources = List.copyOf(sources);
    }
}
