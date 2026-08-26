package dev.nytweetdeck.xapi.profile;

import java.net.URI;
import java.util.Map;

public record AndroidApiProfile(
        String packageName,
        String versionName,
        long versionCode,
        URI restBaseUri,
        URI graphqlBaseUri,
        Map<String, String> standardHeaders,
        Map<String, String> restEndpoints,
        Map<String, GraphQlOperation> graphqlOperations) {

    public AndroidApiProfile {
        standardHeaders = Map.copyOf(standardHeaders);
        restEndpoints = Map.copyOf(restEndpoints);
        graphqlOperations = Map.copyOf(graphqlOperations);
    }

    public record GraphQlOperation(
            String key, String operationId, String operationName, OperationType type) {

        public URI resolveAgainst(URI graphqlBaseUri) {
            var base = graphqlBaseUri.toString();
            var separator = base.endsWith("/") ? "" : "/";
            return URI.create(base + separator + operationId + "/" + operationName);
        }
    }

    public enum OperationType {
        QUERY,
        MUTATION
    }
}
