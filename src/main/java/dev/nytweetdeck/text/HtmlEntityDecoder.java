package dev.nytweetdeck.text;

import java.util.regex.Pattern;

public final class HtmlEntityDecoder {

    private static final Pattern ENTITY = Pattern.compile(
            "&(?:#([0-9]{1,7})|#[xX]([0-9A-Fa-f]{1,6})|([A-Za-z][A-Za-z0-9]{1,31}));");

    private HtmlEntityDecoder() {}

    public static String decode(String value) {
        if (value == null || value.indexOf('&') < 0) {
            return value;
        }
        var matcher = ENTITY.matcher(value);
        var result = new StringBuilder(value.length());
        var copiedUntil = 0;
        var changed = false;
        while (matcher.find()) {
            var replacement = replacement(matcher.group(1), matcher.group(2), matcher.group(3));
            if (replacement == null) {
                continue;
            }
            result.append(value, copiedUntil, matcher.start()).append(replacement);
            copiedUntil = matcher.end();
            changed = true;
        }
        if (!changed) {
            return value;
        }
        return result.append(value, copiedUntil, value.length()).toString();
    }

    private static String replacement(String decimal, String hexadecimal, String named) {
        if (named != null) {
            return switch (named) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> "\u00a0";
                default -> null;
            };
        }
        try {
            var codePoint = Integer.parseInt(decimal != null ? decimal : hexadecimal, decimal != null ? 10 : 16);
            if (codePoint <= 0
                    || !Character.isValidCodePoint(codePoint)
                    || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                return null;
            }
            return new String(Character.toChars(codePoint));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
