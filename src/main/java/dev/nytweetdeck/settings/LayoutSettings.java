package dev.nytweetdeck.settings;

import java.util.List;

public record LayoutSettings(
        Integer version,
        List<Column> columns,
        List<String> navItems,
        String locale,
        String theme,
        String activeAccountId,
        Display display,
        List<String> trendSearchHistory) {

    public record Column(String id, String kind, String target, String label) {}

    public record Display(
            String fontSize,
            String accentColor,
            String density,
            Boolean reduceMotion,
            Boolean mediaPreview,
            Boolean videoAutoplay,
            Boolean videoLoop,
            Integer videoVolume,
            Boolean autoTranslatePosts) {}
}
