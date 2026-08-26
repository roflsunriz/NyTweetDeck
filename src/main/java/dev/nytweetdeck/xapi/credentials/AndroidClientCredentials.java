package dev.nytweetdeck.xapi.credentials;

public record AndroidClientCredentials(String consumerKey, String consumerSecret) {

    public AndroidClientCredentials {
        if (consumerKey == null || consumerKey.isBlank()) {
            throw new IllegalArgumentException("consumer keyが空です。");
        }
        if (consumerSecret == null || consumerSecret.isBlank()) {
            throw new IllegalArgumentException("consumer secretが空です。");
        }
    }

    @Override
    public String toString() {
        return "AndroidClientCredentials[consumerKey=<redacted>, consumerSecret=<redacted>]";
    }
}
