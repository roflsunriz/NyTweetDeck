package dev.nytweetdeck.xapi.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class XApiProfileService {

    private static final String PROFILE_PATH = "x-api/web-current.json";
    private static final String DEFAULTS_PATH = "x-api/web-boolean-feature-defaults.json";

    private final AtomicReference<State> state;

    public XApiProfileService(ObjectMapper objectMapper) {
        try (var profileInput = new ClassPathResource(PROFILE_PATH).getInputStream();
                var defaultsInput = new ClassPathResource(DEFAULTS_PATH).getInputStream()) {
            var profile = objectMapper.readValue(profileInput, XApiProfile.class);
            var defaults = objectMapper
                    .readValue(defaultsInput, FeatureDefaultsDocument.class)
                    .defaults();
            state = new AtomicReference<>(new State(profile, Map.copyOf(defaults)));
        } catch (IOException exception) {
            throw new UncheckedIOException("X Web APIプロファイルを読み込めません。", exception);
        }
    }

    public XApiProfile profile() {
        return state.get().profile();
    }

    public XApiProfile.GraphQlOperation requireOperation(String purpose) {
        var operation = profile().graphqlOperations().get(purpose);
        if (operation == null) {
            throw new IllegalArgumentException("未定義のGraphQL用途です: " + purpose);
        }
        return operation;
    }

    public Map<String, Boolean> selectFeatures(XApiProfile.GraphQlOperation operation) {
        var current = state.get();
        var keys = operation.featureKeys().isEmpty()
                ? current.profile().featureKeys()
                : operation.featureKeys();
        var selected = new LinkedHashMap<String, Boolean>();
        for (var key : keys) {
            var value = current.featureDefaults().get(key);
            if (value == null) {
                throw new IllegalStateException("X Web Feature Switch既定値がありません: " + key);
            }
            selected.put(key, value);
        }
        return Map.copyOf(selected);
    }

    public boolean featureEnabled(String key) {
        return state.get().featureDefaults().getOrDefault(key, false);
    }

    public synchronized int applyResolved(XWebMetadataResolver.ResolvedMetadata metadata) {
        var current = state.get();
        var operations = new LinkedHashMap<String, XApiProfile.GraphQlOperation>();
        for (var entry : current.profile().graphqlOperations().entrySet()) {
            var resolved = metadata.operationsByName().get(entry.getValue().operationName());
            if (resolved == null) {
                throw new IllegalArgumentException(
                        "必須X Web operationが見つかりません: " + entry.getValue().operationName());
            }
            operations.put(
                    entry.getKey(),
                    new XApiProfile.GraphQlOperation(
                            entry.getValue().key(),
                            resolved.operationId(),
                            resolved.operationName(),
                            resolved.type(),
                            resolved.featureKeys(),
                            resolved.fieldToggles()));
        }
        var profile = new XApiProfile(
                current.profile().packageName(),
                metadata.sourceVersion(),
                current.profile().versionCode(),
                current.profile().restBaseUri(),
                current.profile().graphqlBaseUri(),
                current.profile().standardHeaders(),
                current.profile().restEndpoints(),
                metadata.allFeatureKeys(),
                operations);
        state.set(new State(profile, metadata.featureDefaults()));
        return operations.size();
    }

    private record State(XApiProfile profile, Map<String, Boolean> featureDefaults) {}

    private record FeatureDefaultsDocument(Map<String, Boolean> defaults) {}
}
