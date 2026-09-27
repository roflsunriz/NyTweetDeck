package dev.nytweetdeck.post;

import dev.nytweetdeck.account.AccountSecrets;
import dev.nytweetdeck.account.AccountStore;
import dev.nytweetdeck.xapi.http.WebSessionRequestHeaders;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ComposerWebClient {

    private static final URI UPLOAD_URI = URI.create("https://upload.x.com/i/media/upload.json");
    private static final URI POLL_URI = URI.create("https://caps.x.com/v2/cards/create.json");
    private static final URI PLACES_URI = URI.create("https://api.x.com/1.1/geo/places.json");
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_GIF_BYTES = 15 * 1024 * 1024;

    private final HttpClient httpClient;
    private final AccountStore accountStore;
    private final ObjectMapper objectMapper;
    private final URI uploadUri;
    private final URI pollUri;
    private final URI placesUri;

    @Autowired
    public ComposerWebClient(HttpClient httpClient, AccountStore accountStore, ObjectMapper objectMapper) {
        this(httpClient, accountStore, objectMapper, UPLOAD_URI, POLL_URI, PLACES_URI);
    }

    ComposerWebClient(HttpClient httpClient, AccountStore accountStore, ObjectMapper objectMapper,
            URI uploadUri, URI pollUri, URI placesUri) {
        this.httpClient = httpClient;
        this.accountStore = accountStore;
        this.objectMapper = objectMapper;
        this.uploadUri = uploadUri;
        this.pollUri = pollUri;
        this.placesUri = placesUri;
    }

    public MediaUpload upload(String accountId, MultipartFile file) {
        var account = accountStore.requireAccount(accountId);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new XApiHttpException("画像ファイルを読み込めません。", exception);
        }
        var type = mediaType(bytes);
        var limit = "image/gif".equals(type) ? MAX_GIF_BYTES : MAX_IMAGE_BYTES;
        if (bytes.length > limit) {
            throw new IllegalArgumentException("画像またはGIFのサイズが上限を超えています。");
        }
        var init = exchange(account, "media upload INIT", "POST", withQuery(uploadUri, Map.of(
                "command", "INIT", "total_bytes", String.valueOf(bytes.length), "media_type", type)),
                HttpRequest.BodyPublishers.noBody(), null);
        var mediaId = text(init, "media_id_string");
        if (mediaId == null || !mediaId.matches("[0-9]{1,30}")) {
            throw new XApiHttpException("Xが画像IDを返しませんでした。", 502);
        }
        var boundary = "NyTweetDeck-" + UUID.randomUUID();
        var prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"media\"; filename=\"upload\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        exchange(account, "media upload APPEND", "POST", withQuery(uploadUri, Map.of(
                "command", "APPEND", "media_id", mediaId, "segment_index", "0")),
                HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray(bytes),
                        HttpRequest.BodyPublishers.ofByteArray(suffix)),
                "multipart/form-data; boundary=" + boundary);
        var finalized = exchange(account, "media upload FINALIZE", "POST", withQuery(uploadUri, Map.of(
                "command", "FINALIZE", "media_id", mediaId)), HttpRequest.BodyPublishers.noBody(), null);
        awaitProcessing(account, mediaId, finalized);
        return new MediaUpload(mediaId, type);
    }

    public String createPoll(String accountId, PostComposition.Poll poll) {
        if (poll == null || poll.choices().size() < 2 || poll.choices().size() > 4 ||
                poll.durationMinutes() < 5 || poll.durationMinutes() > 10_080 ||
                poll.choices().stream().anyMatch(choice -> choice == null || choice.isBlank() || choice.length() > 25)) {
            throw new IllegalArgumentException("投票の選択肢または期間が不正です。");
        }
        final String cardJson;
        try {
            cardJson = objectMapper.writeValueAsString(pollCardData(poll));
        } catch (JacksonException exception) {
            throw new IllegalStateException("投票データを作成できません。", exception);
        }
        var body = "card_data=" + encode(cardJson);
        var result = exchange(accountStore.requireAccount(accountId), "poll create", "POST", pollUri,
                HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)),
                "application/x-www-form-urlencoded;charset=UTF-8");
        var cardUri = text(result, "card_uri");
        if (!"OK".equals(text(result, "status")) || cardUri == null || cardUri.isBlank()) {
            throw new XApiHttpException("Xが投票カードを作成できませんでした。", 502);
        }
        return cardUri;
    }

    static Map<String, Object> pollCardData(PostComposition.Poll poll) {
        var card = new LinkedHashMap<String, Object>();
        card.put("twitter:card", "poll" + poll.choices().size() + "choice_text_only");
        card.put("twitter:api:api:endpoint", "1");
        card.put("twitter:long:duration_minutes", poll.durationMinutes());
        for (var index = 0; index < poll.choices().size(); index++) {
            card.put("twitter:string:choice" + (index + 1) + "_label", poll.choices().get(index).trim());
        }
        return card;
    }

    public List<Place> searchPlaces(String accountId, String query) {
        var normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new IllegalArgumentException("場所の検索語は1〜100文字で指定してください。");
        }
        var result = exchange(accountStore.requireAccount(accountId), "place search", "GET",
                withQuery(placesUri, Map.of("query_type", "tweet_compose_location", "search_term", normalized)),
                null, null);
        var places = result.path("places");
        if (!places.isArray()) return List.of();
        var matches = new ArrayList<Place>();
        for (var item : places) {
            var place = item.path("place");
            var id = text(place, "id");
            var name = text(place, "full_name");
            if (id != null && name != null) {
                matches.add(new Place(id, name, text(place, "country"),
                        text(item, "geo_search_request_id")));
            }
            if (matches.size() == 20) break;
        }
        return List.copyOf(matches);
    }

    private void awaitProcessing(AccountSecrets account, String mediaId, JsonNode finalized) {
        var response = finalized;
        for (var attempt = 0; attempt < 20; attempt++) {
            var info = response.path("processing_info");
            var state = text(info, "state");
            if (state == null || "succeeded".equals(state)) return;
            if ("failed".equals(state)) throw new XApiHttpException("XがGIFを処理できませんでした。", 502);
            var seconds = Math.max(1, Math.min(5, info.path("check_after_secs").asInt(1)));
            try {
                Thread.sleep(seconds * 1_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new XApiHttpException("GIFの処理確認が中断されました。", exception);
            }
            response = exchange(account, "media upload STATUS", "GET", withQuery(uploadUri, Map.of(
                    "command", "STATUS", "media_id", mediaId)), null, null);
        }
        throw new XApiHttpException("GIFの処理が制限時間内に完了しませんでした。", 502);
    }

    private JsonNode exchange(AccountSecrets account, String purpose, String method, URI uri,
            HttpRequest.BodyPublisher publisher, String contentType) {
        var builder = HttpRequest.newBuilder(uri).version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(45));
        WebSessionRequestHeaders.apply(builder, account, "ja");
        if (contentType != null) builder.header("Content-Type", contentType);
        if ("GET".equals(method)) builder.GET();
        else builder.POST(publisher);
        try {
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new XApiHttpException("Xの" + purpose + "に失敗しました。HTTP " + response.statusCode(),
                        response.statusCode());
            }
            if (response.body().isBlank()) return objectMapper.createObjectNode();
            return objectMapper.readTree(response.body());
        } catch (IOException | JacksonException exception) {
            throw new XApiHttpException("Xの" + purpose + "応答を処理できません。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new XApiHttpException("Xの" + purpose + "が中断されました。", exception);
        }
    }

    private static String mediaType(byte[] bytes) {
        if (bytes.length < 6) throw new IllegalArgumentException("画像ファイルが空または不正です。");
        if ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71) {
            return "image/png";
        }
        if (bytes[0] == 71 && bytes[1] == 73 && bytes[2] == 70 && bytes[3] == 56 &&
                (bytes[4] == 55 || bytes[4] == 57) && bytes[5] == 97) {
            return "image/gif";
        }
        throw new IllegalArgumentException("JPEG・PNG・GIF形式のみアップロードできます。");
    }

    private static URI withQuery(URI uri, Map<String, String> query) {
        var parameters = query.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        return URI.create(uri + "?" + parameters);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String key) {
        var value = node.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    public record MediaUpload(String mediaId, String mimeType) {}

    public record Place(String id, String name, String country, String geoSearchRequestId) {}
}
