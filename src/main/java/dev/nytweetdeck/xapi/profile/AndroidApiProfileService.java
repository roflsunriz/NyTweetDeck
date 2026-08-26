package dev.nytweetdeck.xapi.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AndroidApiProfileService {

    private static final String PROFILE_PATH = "x-api/android-12.19.1.json";

    private final AndroidApiProfile profile;

    public AndroidApiProfileService(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource(PROFILE_PATH).getInputStream()) {
            profile = objectMapper.readValue(input, AndroidApiProfile.class);
        } catch (IOException exception) {
            throw new UncheckedIOException("Android APIプロファイルを読み込めません。", exception);
        }
    }

    public AndroidApiProfile profile() {
        return profile;
    }

    public AndroidApiProfile.GraphQlOperation requireOperation(String purpose) {
        var operation = profile.graphqlOperations().get(purpose);
        if (operation == null) {
            throw new IllegalArgumentException("未定義のGraphQL用途です: " + purpose);
        }
        return operation;
    }
}
