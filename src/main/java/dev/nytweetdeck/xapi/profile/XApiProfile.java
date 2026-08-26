package dev.nytweetdeck.xapi.profile;

import java.net.URI;
import java.util.List;
import java.util.Map;

public record XApiProfile(
        String packageName,
        String versionName,
        long versionCode,
        URI restBaseUri,
        URI graphqlBaseUri,
        Map<String, String> standardHeaders,
        Map<String, String> restEndpoints,
        List<String> featureKeys,
        Map<String, GraphQlOperation> graphqlOperations) {

    public XApiProfile {
        standardHeaders = Map.copyOf(standardHeaders);
        restEndpoints = Map.copyOf(restEndpoints);
        featureKeys = List.copyOf(featureKeys);
        graphqlOperations = Map.copyOf(graphqlOperations);
    }

    public XApiProfile(
            String packageName,
            String versionName,
            long versionCode,
            URI restBaseUri,
            URI graphqlBaseUri,
            Map<String, String> standardHeaders,
            Map<String, String> restEndpoints,
            Map<String, GraphQlOperation> graphqlOperations) {
        this(
                packageName,
                versionName,
                versionCode,
                restBaseUri,
                graphqlBaseUri,
                standardHeaders,
                restEndpoints,
                List.of(),
                graphqlOperations);
    }

    public record GraphQlOperation(
            String key,
            String operationId,
            String operationName,
            OperationType type,
            List<String> featureKeys,
            List<String> fieldToggles) {

        public GraphQlOperation {
            featureKeys = featureKeys == null ? List.of() : List.copyOf(featureKeys);
            fieldToggles = fieldToggles == null ? List.of() : List.copyOf(fieldToggles);
        }

        public GraphQlOperation(
                String key, String operationId, String operationName, OperationType type) {
            this(key, operationId, operationName, type, List.of(), List.of());
        }

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
