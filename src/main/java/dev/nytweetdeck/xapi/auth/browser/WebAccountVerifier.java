package dev.nytweetdeck.xapi.auth.browser;

import dev.nytweetdeck.account.AccountSecrets;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class WebAccountVerifier {

    public AccountSecrets verify(
            Map<String, String> cookies, String bearerToken, BrowserIdentity browserIdentity) {
        var authToken = requireCookie(cookies, "auth_token");
        var csrfToken = requireCookie(cookies, "ct0");
        var userId = parseUserId(requireCookie(cookies, "twid"));
        var username = validUsername(browserIdentity.username())
                ? browserIdentity.username()
                : userId;
        var displayName = browserIdentity.displayName().isBlank()
                ? username
                : browserIdentity.displayName().strip();
        if (displayName.length() > 100) {
            displayName = displayName.substring(0, 100);
        }
        return AccountSecrets.webSession(
                userId,
                userId,
                username,
                displayName,
                bearerToken,
                authToken,
                csrfToken);
    }

    private static String requireCookie(Map<String, String> cookies, String name) {
        var value = cookies.get(name);
        if (value == null || value.isBlank()) {
            throw new XApiHttpException("Xログインセッションに必要なCookieがありません。", 502);
        }
        return value;
    }

    private static String parseUserId(String rawTwid) {
        var decoded = URLDecoder.decode(rawTwid, StandardCharsets.UTF_8);
        var separator = decoded.indexOf('=');
        var value = separator >= 0 ? decoded.substring(separator + 1) : decoded;
        if (!value.matches("[0-9]{1,24}")) {
            throw new XApiHttpException("XログインセッションのユーザーID形式が不正です。", 502);
        }
        return value;
    }

    private static boolean validUsername(String value) {
        return value != null && value.matches("[A-Za-z0-9_]{1,15}");
    }

    public record BrowserIdentity(String username, String displayName) {
        public BrowserIdentity {
            username = username == null ? "" : username;
            displayName = displayName == null ? "" : displayName;
        }
    }
}
