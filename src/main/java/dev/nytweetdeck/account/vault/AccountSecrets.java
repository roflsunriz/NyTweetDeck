package dev.nytweetdeck.account.vault;

public record AccountSecrets(
        String accountId,
        String userId,
        String username,
        String displayName,
        String oauthToken,
        String oauthTokenSecret) {

    public AccountSecrets {
        requireValue(accountId, "accountId");
        requireValue(userId, "userId");
        requireValue(username, "username");
        requireValue(oauthToken, "oauthToken");
        requireValue(oauthTokenSecret, "oauthTokenSecret");
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
                + ", oauthToken=<redacted>, oauthTokenSecret=<redacted>]";
    }

    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "が空です。");
        }
    }
}
