package dev.nytweetdeck.post;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PostTranslationService {

    private static final int MAX_CACHE_ENTRIES = 1_000;
    private static final String LANGUAGE_PATTERN = "[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*";
    private static final String TRANSLATION_SOURCE = "X";

    private final AuthenticatedRestClient restClient;
    private final ObjectMapper objectMapper;
    private final Map<CacheKey, TranslationResult> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, TranslationResult> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    public PostTranslationService(
            AuthenticatedRestClient restClient,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public TranslationResult translate(
            String accountId, String postId, String sourceLanguage, String targetLanguage) {
        PostService.validatePostId(postId);
        var source = normalizeLanguage(sourceLanguage, "元言語");
        var target = normalizeLanguage(targetLanguage, "翻訳先言語");
        if (baseLanguage(source).equals(baseLanguage(target))) {
            throw new IllegalArgumentException("元言語と翻訳先言語が同じです。");
        }
        var key = new CacheKey(accountId, postId, source, target);
        var cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        var response = restClient.get(
                accountId,
                "translatePost",
                Map.of("postId", postId, "translationSource", TRANSLATION_SOURCE),
                Map.of(),
                target);
        var translated = parse(response.rawJson(), postId, source, target);
        cache.put(key, translated);
        return translated;
    }

    TranslationResult parse(
            String body,
            String postId,
            String sourceLanguage,
            String targetLanguage) {
        try {
            var root = objectMapper.readTree(body);
            var responsePostId = text(root, "id_str");
            if (responsePostId != null && !postId.equals(responsePostId)) {
                throw new XApiHttpException("翻訳応答のポストIDが一致しません。", 502);
            }
            var translation = text(root, "translation");
            if (translation == null || translation.isBlank()) {
                throw new XApiHttpException("X翻訳応答に翻訳文がありません。", 502);
            }
            return new TranslationResult(
                    postId, sourceLanguage, targetLanguage, translation, TRANSLATION_SOURCE);
        } catch (JacksonException exception) {
            throw new XApiHttpException("X翻訳応答を解析できません。", exception);
        }
    }

    private static String normalizeLanguage(String value, String label) {
        var normalized = value == null ? "" : value.strip().replace('_', '-');
        if (!normalized.matches(LANGUAGE_PATTERN)) {
            throw new IllegalArgumentException(label + "の形式が不正です。");
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static String baseLanguage(String language) {
        var separator = language.indexOf('-');
        return separator < 0 ? language : language.substring(0, separator);
    }

    private static String text(tools.jackson.databind.JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    private record CacheKey(
            String accountId,
            String postId,
            String sourceLanguage,
            String targetLanguage) {}

    public record TranslationResult(
            String postId,
            String sourceLanguage,
            String targetLanguage,
            String text,
            String provider) {}
}
