package dev.nytweetdeck.settings;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class LayoutSettingsValidator {

    private static final int CURRENT_LAYOUT_VERSION = 8;
    private static final int LEGACY_LAYOUT_VERSION = 7;
    private static final int MAX_HISTORY = 20;
    private static final Set<String> COLUMN_KINDS = Set.of(
            "home", "following", "search", "notifications", "history", "user", "list",
            "messages", "trends");
    private static final Set<String> NAV_ITEMS = Set.of(
            "compose", "search", "home", "notifications", "messages", "trends", "following",
            "chat", "grok", "premium", "profile", "communities", "creatorStudio", "business",
            "ads", "spaces");
    private static final Set<String> LOCALES = Set.of(
            "ja", "en", "zh", "hi", "es", "fr", "ar", "pt", "bn", "ru", "ur");

    private LayoutSettingsValidator() {}

    static LayoutSettings validateAndCopy(LayoutSettings settings) {
        if (settings == null || settings.version() == null
                || (settings.version() != CURRENT_LAYOUT_VERSION
                        && settings.version() != LEGACY_LAYOUT_VERSION)) {
            throw new IllegalArgumentException("未対応のレイアウト設定版です。");
        }
        var columns = validateColumns(settings.columns());
        var navItems = validateUniqueValues(settings.navItems(), NAV_ITEMS, 100, "メニュー項目");
        if (!LOCALES.contains(settings.locale())) {
            throw new IllegalArgumentException("表示言語が不正です。");
        }
        if (!Set.of("system", "light", "dark").contains(settings.theme())) {
            throw new IllegalArgumentException("テーマが不正です。");
        }
        validateNullableText(settings.activeAccountId(), 200, "選択アカウント");
        var replySort = settings.version() == LEGACY_LAYOUT_VERSION
                ? "relevance"
                : settings.replySort();
        if (replySort == null
                || !Set.of("relevance", "recency", "likes").contains(replySort)) {
            throw new IllegalArgumentException("返信の並び順が不正です。");
        }
        var display = validateDisplay(settings.display());
        var history = validateHistory(settings.trendSearchHistory());
        return new LayoutSettings(
                CURRENT_LAYOUT_VERSION,
                columns,
                navItems,
                settings.locale(),
                settings.theme(),
                settings.activeAccountId(),
                replySort,
                display,
                history);
    }

    private static List<LayoutSettings.Column> validateColumns(
            List<LayoutSettings.Column> columns) {
        if (columns == null) {
            throw new IllegalArgumentException("カラム設定が不正です。");
        }
        var ids = new HashSet<String>();
        for (var column : columns) {
            if (column == null) {
                throw new IllegalArgumentException("カラム設定に空要素があります。");
            }
            validateRequiredText(column.id(), 200, "カラムID");
            if (!ids.add(column.id())) {
                throw new IllegalArgumentException("カラムIDが重複しています。");
            }
            if (!COLUMN_KINDS.contains(column.kind())) {
                throw new IllegalArgumentException("カラム種別が不正です。");
            }
            validateNullableText(column.target(), 500, "カラム対象");
            validateNullableText(column.label(), 500, "カラム名");
        }
        return List.copyOf(columns);
    }

    private static List<String> validateUniqueValues(
            List<String> values, Set<String> allowed, int maximum, String label) {
        if (values == null || values.size() > maximum) {
            throw new IllegalArgumentException(label + "が不正です。");
        }
        var unique = new HashSet<String>();
        for (var value : values) {
            if (!allowed.contains(value) || !unique.add(value)) {
                throw new IllegalArgumentException(label + "に不明または重複した値があります。");
            }
        }
        return List.copyOf(values);
    }

    private static LayoutSettings.Display validateDisplay(LayoutSettings.Display display) {
        if (display == null
                || !Set.of("small", "default", "large").contains(display.fontSize())
                || !Set.of("blue", "yellow", "pink", "purple", "orange", "green")
                        .contains(display.accentColor())
                || !Set.of("comfortable", "compact").contains(display.density())
                || display.reduceMotion() == null
                || display.mediaPreview() == null
                || display.videoAutoplay() == null
                || display.videoLoop() == null
                || display.autoTranslatePosts() == null
                || display.videoVolume() == null
                || display.videoVolume() < 0
                || display.videoVolume() > 100) {
            throw new IllegalArgumentException("表示設定が不正です。");
        }
        return display;
    }

    private static List<String> validateHistory(List<String> history) {
        if (history == null || history.size() > MAX_HISTORY) {
            throw new IllegalArgumentException("検索履歴が不正です。");
        }
        var unique = new HashSet<String>();
        for (var value : history) {
            validateRequiredText(value, 100, "検索履歴");
            if (!value.equals(value.trim()) || !unique.add(value.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("検索履歴に空白または重複があります。");
            }
        }
        return List.copyOf(history);
    }

    private static void validateRequiredText(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + "が不正です。");
        }
    }

    private static void validateNullableText(String value, int maximum, String label) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(label + "が長すぎます。");
        }
    }
}
