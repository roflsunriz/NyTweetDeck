package dev.nytweetdeck.xapi.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XWebMetadataResolverTest {

    private final XWebMetadataResolver resolver = new XWebMetadataResolver(null);

    @Test
    void extractsOperationMetadataWithoutExecutingOfficialJavascript() {
        var operations = resolver.parseOperations("""
                436870(e){e.exports={queryId:"wp06oo3fRGU4P1sK8rECqQ",operationName:"HomeTimeline",
                operationType:"query",metadata:{featureSwitches:["feature_a","feature_b"],
                fieldToggles:["withArticlePlainText"]}}}
                """);

        assertThat(operations).containsKey("HomeTimeline");
        var operation = operations.get("HomeTimeline");
        assertThat(operation.operationId()).isEqualTo("wp06oo3fRGU4P1sK8rECqQ");
        assertThat(operation.featureKeys()).containsExactly("feature_a", "feature_b");
        assertThat(operation.fieldToggles()).containsExactly("withArticlePlainText");
    }

    @Test
    void extractsBooleanFeatureDefaultsAndSafeChunkManifestEntries() {
        var html = """
                {1:"bundle.HomeTimeline",2:"icons.1",1:"0123456789abcdef",2:"fedcba9876543210"}
                "feature_a":{"value":true},"feature_b":{"value":false}
                """;

        assertThat(resolver.parseBooleanFeatures(html))
                .containsEntry("feature_a", true)
                .containsEntry("feature_b", false);
        assertThat(resolver.parseChunkCandidates(html))
                .extracting(XWebMetadataResolver.ChunkCandidate::name)
                .contains("bundle.HomeTimeline", "icons.1");
    }
}
