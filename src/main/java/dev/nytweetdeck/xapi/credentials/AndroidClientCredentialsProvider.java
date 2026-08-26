package dev.nytweetdeck.xapi.credentials;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AndroidClientCredentialsProvider {

    private final Path credentialsPath;

    public AndroidClientCredentialsProvider(
            @Value("${nytweetdeck.x-api.credentials-path:.local/android-client.properties}")
                    String credentialsPath) {
        this.credentialsPath = Path.of(credentialsPath).toAbsolutePath().normalize();
    }

    public Optional<AndroidClientCredentials> find() {
        if (!Files.isRegularFile(credentialsPath)) {
            return Optional.empty();
        }
        var properties = new Properties();
        try (InputStream input = Files.newInputStream(credentialsPath)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Androidクライアント資格情報を読み込めません: " + credentialsPath, exception);
        }
        return Optional.of(new AndroidClientCredentials(
                properties.getProperty("consumerKey"), properties.getProperty("consumerSecret")));
    }

    public AndroidClientCredentials require() {
        return find().orElseThrow(() -> new IllegalStateException(
                "Androidクライアント資格情報が未設定です。"
                        + " APKからの資格情報準備には明示的な承認が必要です。"));
    }

    public Path credentialsPath() {
        return credentialsPath;
    }
}
