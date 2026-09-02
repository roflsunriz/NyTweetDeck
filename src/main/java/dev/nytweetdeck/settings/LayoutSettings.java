package dev.nytweetdeck.settings;

import java.util.List;

public record LayoutSettings(
        Integer version,
        List<Column> columns,
        List<String> navItems,
        String locale,
        String translationLocale,
        String theme,
        String activeAccountId,
        String replySort,
        Display display,
        List<String> trendSearchHistory) {

    public LayoutSettings(
            Integer version,
            List<Column> columns,
            List<String> navItems,
            String locale,
            String theme,
            String activeAccountId,
            String replySort,
            Display display,
            List<String> trendSearchHistory) {
        this(version, columns, navItems, locale, locale, theme, activeAccountId, replySort, display,
                trendSearchHistory);
    }

    public record Column(String id, String kind, String target, String label, String sort) {
        public Column(String id, String kind, String target, String label) {
            this(id, kind, target, label, "latest");
        }
    }

    public record Display(
            String fontSize,
            String accentColor,
            String density,
            Boolean reduceMotion,
            Boolean mediaPreview,
            Boolean videoAutoplay,
            Boolean videoLoop,
            Integer videoVolume,
            Boolean autoTranslatePosts,
            Boolean autoRefreshTimelines,
            String videoQuality,
            String navigationPosition,
            Boolean showMainNavigation) {
        public Display(
                String fontSize,
                String accentColor,
                String density,
                Boolean reduceMotion,
                Boolean mediaPreview,
                Boolean videoAutoplay,
                Boolean videoLoop,
                Integer videoVolume,
                Boolean autoTranslatePosts,
                Boolean autoRefreshTimelines,
                String videoQuality,
                String navigationPosition) {
            this(fontSize, accentColor, density, reduceMotion, mediaPreview, videoAutoplay,
                    videoLoop, videoVolume, autoTranslatePosts, autoRefreshTimelines, videoQuality,
                    navigationPosition, true);
        }

        public Display(
                String fontSize,
                String accentColor,
                String density,
                Boolean reduceMotion,
                Boolean mediaPreview,
                Boolean videoAutoplay,
                Boolean videoLoop,
                Integer videoVolume,
                Boolean autoTranslatePosts) {
            this(fontSize, accentColor, density, reduceMotion, mediaPreview, videoAutoplay,
                    videoLoop, videoVolume, autoTranslatePosts, true, "auto", "left", true);
        }
    }
}
