package dev.nytweetdeck.xapi.http;

import dev.nytweetdeck.xapi.auth.browser.WebBearerTokenProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Generates the per-request transaction header used by X's responsive Web client. */
@Service
public class XClientTransactionIdService {

    private static final URI X_HOME_URI = URI.create("https://x.com/home");
    private static final URI WEB_ASSET_BASE_URI =
            URI.create("https://abs.twimg.com/responsive-web/client-web/");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_CACHE_DURATION = Duration.ofMinutes(30);
    private static final long X_TIME_EPOCH_SECONDS = 1_682_924_400L;
    private static final String DEFAULT_KEYWORD = "obfiowerehiring";
    private static final int ADDITIONAL_RANDOM_NUMBER = 3;

    private static final Pattern META_TAG_PATTERN = Pattern.compile(
            "<meta\\b[^>]*\\bname\\s*=\\s*([\"'])twitter-site-verification\\1[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\bcontent\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern ON_DEMAND_CHUNK_PATTERN = Pattern.compile(
            "(\\d+):\\s*([\"'])ondemand\\.s\\2", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_PATTERN =
            Pattern.compile("\\(\\w\\[(\\d{1,2})],\\s*16\\)");
    private static final Pattern SVG_PATTERN = Pattern.compile(
            "<svg\\b[^>]*\\bid\\s*=\\s*([\"'])loading-x-anim-[^\"']+\\1[^>]*>(.*?)</svg>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "<path\\b[^>]*\\bd\\s*=\\s*([\"'])(.*?)\\1[^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NON_DIGITS_PATTERN = Pattern.compile("[^\\d]+");

    private final HttpClient httpClient;
    private final Clock clock;
    private final IntSupplier randomByteSupplier;
    private final Duration cacheDuration;
    private volatile SigningMaterial cachedMaterial;
    private volatile Instant cachedAt;

    @Autowired
    public XClientTransactionIdService(HttpClient httpClient) {
        var secureRandom = new SecureRandom();
        this.httpClient = httpClient;
        this.clock = Clock.systemUTC();
        this.randomByteSupplier = () -> secureRandom.nextInt(256);
        this.cacheDuration = DEFAULT_CACHE_DURATION;
    }

    XClientTransactionIdService(
            HttpClient httpClient,
            Clock clock,
            IntSupplier randomByteSupplier,
            Duration cacheDuration) {
        this.httpClient = httpClient;
        this.clock = clock;
        this.randomByteSupplier = randomByteSupplier;
        this.cacheDuration = cacheDuration;
    }

    public String generate(String method, URI requestUri) {
        var material = signingMaterial();
        long timeNow = clock.instant().getEpochSecond() - X_TIME_EPOCH_SECONDS;
        return encode(
                method.toUpperCase(Locale.ROOT),
                requestUri.getPath(),
                material.keyBytes(),
                material.animationKey(),
                timeNow,
                randomByteSupplier.getAsInt());
    }

    public synchronized void invalidate() {
        cachedMaterial = null;
        cachedAt = null;
    }

    private SigningMaterial signingMaterial() {
        var material = cachedMaterial;
        var loadedAt = cachedAt;
        if (material != null
                && loadedAt != null
                && loadedAt.plus(cacheDuration).isAfter(clock.instant())) {
            return material;
        }
        synchronized (this) {
            material = cachedMaterial;
            loadedAt = cachedAt;
            if (material != null
                    && loadedAt != null
                    && loadedAt.plus(cacheDuration).isAfter(clock.instant())) {
                return material;
            }
            material = loadSigningMaterial();
            cachedMaterial = material;
            cachedAt = clock.instant();
            return material;
        }
    }

    private SigningMaterial loadSigningMaterial() {
        var homeHtml = fetchText(X_HOME_URI, "text/html,application/xhtml+xml");
        var onDemandUri = resolveOnDemandUri(homeHtml);
        var onDemandSource = fetchText(onDemandUri, "*/*");
        return parseSigningMaterial(homeHtml, onDemandSource);
    }

    private String fetchText(URI uri, String accept) {
        var request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", accept)
                .header("Accept-Language", "ja")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("User-Agent", WebBearerTokenProvider.BROWSER_USER_AGENT)
                .GET()
                .build();
        try {
            var response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new XApiHttpException(
                        "X Web署名情報を取得できませんでした。HTTP " + response.statusCode(),
                        response.statusCode());
            }
            return response.body();
        } catch (IOException exception) {
            throw new XApiHttpException("X Web署名情報の通信に失敗しました。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new XApiHttpException("X Web署名情報の取得が中断されました。", exception);
        }
    }

    static URI resolveOnDemandUri(String homeHtml) {
        var chunkMatcher = ON_DEMAND_CHUNK_PATTERN.matcher(homeHtml);
        if (!chunkMatcher.find()) {
            throw new XApiHttpException("X Web署名チャンクIDを解決できませんでした。", 502);
        }
        var chunkId = Pattern.quote(chunkMatcher.group(1));
        var hashPattern = Pattern.compile(
                "\\b" + chunkId + ":\\s*([\"'])([a-zA-Z0-9_-]+)\\1");
        var hashMatcher = hashPattern.matcher(homeHtml);
        String hash = null;
        while (hashMatcher.find()) {
            hash = hashMatcher.group(2);
        }
        if (hash == null) {
            throw new XApiHttpException("X Web署名チャンクを解決できませんでした。", 502);
        }
        return WEB_ASSET_BASE_URI.resolve("ondemand.s." + hash + "a.js");
    }

    static SigningMaterial parseSigningMaterial(String homeHtml, String onDemandSource) {
        var metaMatcher = META_TAG_PATTERN.matcher(homeHtml);
        if (!metaMatcher.find()) {
            throw new XApiHttpException("X Web署名キーが見つかりません。", 502);
        }
        var contentMatcher = CONTENT_ATTRIBUTE_PATTERN.matcher(metaMatcher.group());
        if (!contentMatcher.find() || contentMatcher.group(2).isBlank()) {
            throw new XApiHttpException("X Web署名キーが空です。", 502);
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(contentMatcher.group(2));
        } catch (IllegalArgumentException exception) {
            throw new XApiHttpException("X Web署名キーを解析できません。", exception);
        }

        var indices = new ArrayList<Integer>();
        var indexMatcher = INDEX_PATTERN.matcher(onDemandSource);
        while (indexMatcher.find()) {
            indices.add(Integer.parseInt(indexMatcher.group(1)));
        }
        if (indices.size() < 2) {
            throw new XApiHttpException("X Web署名インデックスを解析できません。", 502);
        }

        int rowIndexKey = indices.get(0);
        var frameTimeKeys = indices.subList(1, indices.size());
        if (rowIndexKey >= keyBytes.length
                || frameTimeKeys.stream().anyMatch(index -> index >= keyBytes.length)
                || keyBytes.length <= 5) {
            throw new XApiHttpException("X Web署名インデックスが範囲外です。", 502);
        }

        var frames = parseFrames(homeHtml);
        int frameIndex = unsigned(keyBytes[5]) % 4;
        if (frameIndex >= frames.size()) {
            throw new XApiHttpException("X Web署名アニメーションが不足しています。", 502);
        }
        var rows = frames.get(frameIndex);
        int rowIndex = unsigned(keyBytes[rowIndexKey]) % 16;
        if (rowIndex >= rows.size()) {
            throw new XApiHttpException("X Web署名アニメーション行が不足しています。", 502);
        }

        long frameTimeProduct = 1;
        for (int index : frameTimeKeys) {
            frameTimeProduct *= unsigned(keyBytes[index]) % 16;
        }
        long frameTime = Math.round(frameTimeProduct / 10.0) * 10;
        var animationKey = animate(rows.get(rowIndex), frameTime / 4096.0);
        return new SigningMaterial(keyBytes, animationKey);
    }

    private static List<List<List<Integer>>> parseFrames(String homeHtml) {
        var frames = new ArrayList<List<List<Integer>>>();
        var svgMatcher = SVG_PATTERN.matcher(homeHtml);
        while (svgMatcher.find()) {
            var paths = new ArrayList<String>();
            var pathMatcher = PATH_PATTERN.matcher(svgMatcher.group(2));
            while (pathMatcher.find()) {
                paths.add(pathMatcher.group(2));
            }
            if (paths.size() < 2) {
                throw new XApiHttpException("X Web署名SVGを解析できません。", 502);
            }
            var pathData = paths.get(1);
            if (pathData.length() < 9) {
                throw new XApiHttpException("X Web署名SVGのパスが不正です。", 502);
            }
            var rows = new ArrayList<List<Integer>>();
            for (String segment : pathData.substring(9).split("C")) {
                var cleaned = NON_DIGITS_PATTERN.matcher(segment).replaceAll(" ").trim();
                var values = new ArrayList<Integer>();
                if (!cleaned.isEmpty()) {
                    for (String value : cleaned.split("\\s+")) {
                        values.add(Integer.parseInt(value));
                    }
                }
                rows.add(values);
            }
            frames.add(rows);
        }
        if (frames.isEmpty()) {
            throw new XApiHttpException("X Web署名アニメーションが見つかりません。", 502);
        }
        return frames;
    }

    private static String animate(List<Integer> frame, double targetTime) {
        if (frame.size() < 11) {
            throw new XApiHttpException("X Web署名アニメーションデータが不正です。", 502);
        }
        var fromColor = new double[] {frame.get(0), frame.get(1), frame.get(2), 1};
        var toColor = new double[] {frame.get(3), frame.get(4), frame.get(5), 1};
        var toRotation = solve(frame.get(6), 60, 360, true);
        var curves = new double[frame.size() - 7];
        for (int index = 7; index < frame.size(); index++) {
            curves[index - 7] = solve(frame.get(index), (index - 7) % 2 == 1 ? -1 : 0, 1, false);
        }

        var value = cubicValue(curves, targetTime);
        var output = new StringBuilder();
        for (int index = 0; index < 3; index++) {
            var color = Math.max(fromColor[index] * (1 - value) + toColor[index] * value, 0);
            output.append(Long.toHexString(Math.round(color)));
        }

        var rotation = toRotation * value;
        var radians = rotation * Math.PI / 180;
        var matrix = new double[] {
            Math.cos(radians), -Math.sin(radians), Math.sin(radians), Math.cos(radians)
        };
        for (double matrixValue : matrix) {
            var rounded = Math.round(matrixValue * 100) / 100.0;
            if (rounded < 0) {
                rounded = -rounded;
            }
            var hex = floatToHex(rounded);
            output.append(hex.startsWith(".") ? "0" + hex.toLowerCase(Locale.ROOT) : hex.isEmpty() ? "0" : hex);
        }
        output.append("00");
        return output.toString().replaceAll("[.-]", "");
    }

    private static double solve(int value, double minimum, double maximum, boolean floor) {
        var result = value * (maximum - minimum) / 255 + minimum;
        return floor ? Math.floor(result) : Math.round(result * 100) / 100.0;
    }

    private static double cubicValue(double[] curves, double time) {
        if (curves.length < 4) {
            throw new XApiHttpException("X Web署名カーブが不足しています。", 502);
        }
        if (time <= 0) {
            double gradient = curves[0] > 0
                    ? curves[1] / curves[0]
                    : curves[1] == 0 && curves[2] > 0 ? curves[3] / curves[2] : 0;
            return gradient * time;
        }
        if (time >= 1) {
            double gradient = curves[2] < 1
                    ? (curves[3] - 1) / (curves[2] - 1)
                    : curves[2] == 1 && curves[0] < 1
                            ? (curves[1] - 1) / (curves[0] - 1)
                            : 0;
            return 1 + gradient * (time - 1);
        }
        double start = 0;
        double middle = 0;
        double end = 1;
        for (int iteration = 0; iteration < 100; iteration++) {
            middle = (start + end) / 2;
            var estimate = cubicCoordinate(curves[0], curves[2], middle);
            if (Math.abs(time - estimate) < 0.00001) {
                break;
            }
            if (estimate < time) {
                start = middle;
            } else {
                end = middle;
            }
        }
        return cubicCoordinate(curves[1], curves[3], middle);
    }

    private static double cubicCoordinate(double a, double b, double middle) {
        return 3 * a * (1 - middle) * (1 - middle) * middle
                + 3 * b * (1 - middle) * middle * middle
                + middle * middle * middle;
    }

    private static String floatToHex(double value) {
        var result = new StringBuilder();
        var quotient = Math.floor(value);
        var fraction = value - quotient;
        while (quotient > 0) {
            var nextQuotient = Math.floor(value / 16);
            int remainder = (int) Math.floor(value - nextQuotient * 16);
            result.insert(0, Character.forDigit(remainder, 16));
            value = nextQuotient;
            quotient = nextQuotient;
        }
        if (fraction == 0) {
            return result.toString();
        }
        result.append('.');
        for (int digits = 0; fraction > 0 && digits < 32; digits++) {
            fraction *= 16;
            int integer = (int) Math.floor(fraction);
            fraction -= integer;
            result.append(Character.forDigit(integer, 16));
        }
        return result.toString();
    }

    static String encode(
            String method,
            String path,
            byte[] keyBytes,
            String animationKey,
            long timeNow,
            int randomByte) {
        var data = method + "!" + path + "!" + timeNow + DEFAULT_KEYWORD + animationKey;
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256を利用できません。", exception);
        }
        var bytes = new byte[keyBytes.length + 4 + 16 + 1];
        System.arraycopy(keyBytes, 0, bytes, 0, keyBytes.length);
        int offset = keyBytes.length;
        bytes[offset] = (byte) timeNow;
        bytes[offset + 1] = (byte) (timeNow >> 8);
        bytes[offset + 2] = (byte) (timeNow >> 16);
        bytes[offset + 3] = (byte) (timeNow >> 24);
        System.arraycopy(hash, 0, bytes, offset + 4, 16);
        bytes[bytes.length - 1] = ADDITIONAL_RANDOM_NUMBER;

        int mask = randomByte & 0xff;
        var output = new byte[bytes.length + 1];
        output[0] = (byte) mask;
        for (int index = 0; index < bytes.length; index++) {
            output[index + 1] = (byte) (unsigned(bytes[index]) ^ mask);
        }
        return Base64.getEncoder().withoutPadding().encodeToString(output);
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    record SigningMaterial(byte[] keyBytes, String animationKey) {
        SigningMaterial {
            keyBytes = keyBytes.clone();
        }

        @Override
        public byte[] keyBytes() {
            return keyBytes.clone();
        }
    }
}
