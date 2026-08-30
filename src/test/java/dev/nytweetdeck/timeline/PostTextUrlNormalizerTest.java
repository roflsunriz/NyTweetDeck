package dev.nytweetdeck.timeline;

import static dev.nytweetdeck.timeline.PostTextUrlNormalizer.Kind.ARTICLE;
import static dev.nytweetdeck.timeline.PostTextUrlNormalizer.Kind.LINK;
import static dev.nytweetdeck.timeline.PostTextUrlNormalizer.Kind.MEDIA;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PostTextUrlNormalizerTest {

    @Test
    void expandsOrdinaryLinksAndPrefersTheUnwoundDestination() {
        var entities = List.of(new PostTextUrlNormalizer.UrlEntity(
                "https://t.co/docs", "https://example.com/short", "https://example.com/original?a=1", LINK));

        assertThat(PostTextUrlNormalizer.normalize("資料 https://t.co/docs", entities))
                .isEqualTo("資料 https://example.com/original?a=1");
    }

    @Test
    void removesMediaAndArticleRedirectsWithoutTouchingLookalikeHosts() {
        var entities = List.of(
                new PostTextUrlNormalizer.UrlEntity("https://t.co/media", null, null, MEDIA),
                new PostTextUrlNormalizer.UrlEntity("https://t.co/article", null, null, ARTICLE),
                new PostTextUrlNormalizer.UrlEntity(
                        "https://not-t.co/link", "https://example.com/original", null, LINK));

        assertThat(PostTextUrlNormalizer.normalize(
                        "本文 https://t.co/media https://t.co/article https://not-t.co/link", entities))
                .isEqualTo("本文 https://not-t.co/link");
    }

    @Test
    void keepsTheShortUrlWhenTheDestinationIsMissingOrUnsafe() {
        var entities = List.of(new PostTextUrlNormalizer.UrlEntity(
                "https://t.co/docs", "javascript:alert(1)", null, LINK));

        assertThat(PostTextUrlNormalizer.normalize("https://t.co/docs", entities))
                .isEqualTo("https://t.co/docs");
    }
}
