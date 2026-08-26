package dev.nytweetdeck.xapi.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AndroidFeatureDefaultsService {

    private static final String DEFAULTS_PATH = "x-api/android-boolean-feature-defaults.json";

    private final FeatureDefaultsDocument document;

    public AndroidFeatureDefaultsService(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource(DEFAULTS_PATH).getInputStream()) {
            document = objectMapper.readValue(input, FeatureDefaultsDocument.class);
        } catch (IOException exception) {
            throw new UncheckedIOException("Android Feature Switch既定値を読み込めません。", exception);
        }
    }

    public Map<String, Boolean> selectFor(AndroidApiProfile profile) {
        var selected = new LinkedHashMap<String, Boolean>();
        for (String key : profile.featureKeys()) {
            var value = document.defaults().get(key);
            if (value == null) {
                throw new IllegalStateException("Android Feature Switch既定値がありません: " + key);
            }
            selected.put(key, value);
        }
        return Map.copyOf(selected);
    }

    public int extractedCount() {
        return document.count();
    }

    private record FeatureDefaultsDocument(
            Source source, int count, Map<String, Boolean> defaults) {}

    private record Source(
            String packageName, String versionName, long versionCode, String featureSetToken) {}
}
