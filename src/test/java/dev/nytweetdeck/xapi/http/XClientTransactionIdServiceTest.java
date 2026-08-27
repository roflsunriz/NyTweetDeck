package dev.nytweetdeck.xapi.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class XClientTransactionIdServiceTest {

    @Test
    void resolvesCurrentOnDemandAssetFromTheWebRuntimeMap() {
        var html = "59924:\"ondemand.s\",other})[e]||e)+\".\"+({59924:\"e89b799f9742fd4e\"";

        assertThat(XClientTransactionIdService.resolveOnDemandUri(html))
                .isEqualTo(URI.create(
                        "https://abs.twimg.com/responsive-web/client-web/ondemand.s.e89b799f9742fd4ea.js"));
    }

    @Test
    void parsesTheVerificationKeyIndicesAndLoadingAnimation() {
        var keyBytes = new byte[24];
        for (int index = 0; index < keyBytes.length; index++) {
            keyBytes[index] = (byte) (index + 1);
        }
        keyBytes[5] = 4;
        var key = Base64.getEncoder().encodeToString(keyBytes);
        var rows = new StringBuilder();
        for (int index = 0; index < 16; index++) {
            rows.append("C 10,20 30,40 50,60 70,80 90,100 110 ");
        }
        var frames = new StringBuilder();
        for (int index = 0; index < 4; index++) {
            frames.append("<svg id=\"loading-x-anim-")
                    .append(index)
                    .append("\"><g><path d=\"M0\"></path><path d=\"M 10,30 ")
                    .append(rows)
                    .append("\"></path></g></svg>");
        }
        var html = "<meta name=\"twitter-site-verification\" content=\""
                + key
                + "\">"
                + frames;
        var source = "(a[2], 16)(b[17], 16)(c[3], 16)(d[9], 16)";

        var material = XClientTransactionIdService.parseSigningMaterial(html, source);

        assertThat(material.keyBytes()).containsExactly(keyBytes);
        assertThat(material.animationKey()).isNotBlank().doesNotContain(".", "-");
    }

    @Test
    void encodesTheMethodPathTimestampHashAndRandomMaskDeterministically() {
        var encoded = XClientTransactionIdService.encode(
                "POST",
                "/i/api/graphql/id/CreateRetweet",
                Base64.getDecoder().decode("AQIDBAUGBwg="),
                "abcdef",
                123_456_789L,
                42);

        assertThat(encoded).isEqualTo("KisoKS4vLC0iP+dxLVBSfQNSOMLgRgeCHetFQtUp");
    }
}
