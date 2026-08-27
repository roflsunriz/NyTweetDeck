package dev.nytweetdeck.xapi.http;

import dev.nytweetdeck.account.vault.AccountSecrets;
import dev.nytweetdeck.xapi.auth.browser.WebBearerTokenProvider;
import java.net.http.HttpRequest;

public final class WebSessionRequestHeaders {

    private WebSessionRequestHeaders() {}

    public static void apply(HttpRequest.Builder builder, AccountSecrets account, String language) {
        if (!account.hasWebSession()) {
            throw new IllegalArgumentException("Webセッション資格情報がありません。");
        }
        builder.header("Authorization", "Bearer " + account.webBearerToken());
        builder.header("Cookie", "auth_token=" + account.authToken() + "; ct0=" + account.csrfToken());
        builder.header("X-CSRF-Token", account.csrfToken());
        builder.header("X-Twitter-Auth-Type", "OAuth2Session");
        builder.header("X-Twitter-Active-User", "yes");
        builder.header("X-Twitter-Client-Language", language);
        builder.header("Accept-Language", language);
        builder.header("Origin", "https://x.com");
        builder.header("Referer", "https://x.com/");
        builder.header("User-Agent", WebBearerTokenProvider.BROWSER_USER_AGENT);
        builder.header("Accept", "application/json");
    }
}
