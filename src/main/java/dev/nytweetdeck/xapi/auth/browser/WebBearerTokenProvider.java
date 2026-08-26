package dev.nytweetdeck.xapi.auth.browser;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class WebBearerTokenProvider {

    public static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final URI X_HOME = URI.create("https://x.com/");
    private static final URI ASSET_BASE = URI.create("https://abs.twimg.com/x-web/x-web/");
    private static final Pattern ENTRY_ASSET = Pattern.compile(
            "https://abs\\.twimg\\.com/x-web/x-web/(entry-client-logged-out-[A-Za-z0-9_-]+\\.js)");
    private static final Pattern GUEST_ASSET =
            Pattern.compile("assets/guest-token-[A-Za-z0-9_-]+\\.js");
    private static final Pattern BEARER = Pattern.compile("Bearer (AAAAA[^`]+)");

    private final HttpClient httpClient;
    private volatile String cached;

    public WebBearerTokenProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public String require() {
        var current = cached;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cached == null) {
                cached = resolve();
            }
            return cached;
        }
    }

    public synchronized void invalidate() {
        cached = null;
    }

    private String resolve() {
        var home = get(X_HOME, "X公式ログインページ");
        var entryMatcher = ENTRY_ASSET.matcher(home);
        if (!entryMatcher.find()) {
            throw new XApiHttpException("X公式ログイン資産を検出できません。", 502);
        }
        var entry = get(ASSET_BASE.resolve(entryMatcher.group(1)), "X公式ログイン資産");
        var guestMatcher = GUEST_ASSET.matcher(entry);
        if (!guestMatcher.find()) {
            throw new XApiHttpException("X公式Guest認証資産を検出できません。", 502);
        }
        var guestAsset = get(ASSET_BASE.resolve(guestMatcher.group()), "X公式Guest認証資産");
        var bearerMatcher = BEARER.matcher(guestAsset);
        if (!bearerMatcher.find()) {
            throw new XApiHttpException("X公式Web Bearerを検出できません。", 502);
        }
        return URLDecoder.decode(bearerMatcher.group(1), StandardCharsets.UTF_8);
    }

    private String get(URI uri, String source) {
        var request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", BROWSER_USER_AGENT)
                .GET()
                .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new XApiHttpException(source + "の取得に失敗しました。HTTP " + response.statusCode(), response.statusCode());
            }
            return response.body();
        } catch (IOException exception) {
            throw new XApiHttpException(source + "の通信に失敗しました。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new XApiHttpException(source + "の取得が中断されました。", exception);
        }
    }
}
