package dev.nytweetdeck.account.vault;

public record AccountSecrets(
        String accountId,
        String userId,
        String username,
        String displayName,
        String webBearerToken,
        String authToken,
        String csrfToken) {

    public AccountSecrets {
        requireValue(accountId, "accountId");
        requireValue(userId, "userId");
        requireValue(username, "username");
        var webReady = hasValue(webBearerToken) && hasValue(authToken) && hasValue(csrfToken);
        if (!webReady) {
            throw new IllegalArgumentException("Webセッション資格情報が必要です。");
        }
    }

    public static AccountSecrets webSession(
            String accountId,
            String userId,
            String username,
            String displayName,
            String webBearerToken,
            String authToken,
            String csrfToken) {
        return new AccountSecrets(
                accountId,
                userId,
                username,
                displayName,
                webBearerToken,
                authToken,
                csrfToken);
    }

    public boolean hasWebSession() {
        return hasValue(webBearerToken) && hasValue(authToken) && hasValue(csrfToken);
    }

    @Override
    public String toString() {
        return "AccountSecrets[accountId="
                + accountId
                + ", userId="
                + userId
                + ", username="
                + username
                + ", displayName="
                + displayName
                + ", webBearerToken=<redacted>, authToken=<redacted>, csrfToken=<redacted>]";
    }

    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "が空です。");
        }
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }
}
