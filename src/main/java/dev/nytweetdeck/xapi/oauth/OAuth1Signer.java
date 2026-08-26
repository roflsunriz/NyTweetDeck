package dev.nytweetdeck.xapi.oauth;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class OAuth1Signer {

    private static final String HMAC_SHA1 = "HmacSHA1";

    public String authorizationHeader(
            String method,
            URI uri,
            List<Parameter> bodyParameters,
            Credentials credentials,
            String timestamp,
            String nonce) {
        return authorizationHeader(
                method, uri, bodyParameters, credentials, timestamp, nonce, true);
    }

    String authorizationHeader(
            String method,
            URI uri,
            List<Parameter> bodyParameters,
            Credentials credentials,
            String timestamp,
            String nonce,
            boolean includeOAuthVersion) {
        var oauthParameters = new ArrayList<Parameter>();
        oauthParameters.add(new Parameter("oauth_consumer_key", credentials.consumerKey()));
        oauthParameters.add(new Parameter("oauth_nonce", nonce));
        oauthParameters.add(new Parameter("oauth_signature_method", "HMAC-SHA1"));
        oauthParameters.add(new Parameter("oauth_timestamp", timestamp));
        if (includeOAuthVersion) {
            oauthParameters.add(new Parameter("oauth_version", "1.0"));
        }
        if (credentials.token() != null) {
            oauthParameters.add(new Parameter("oauth_token", credentials.token()));
        }

        var signatureParameters = new ArrayList<Parameter>();
        signatureParameters.addAll(parseQuery(uri.getRawQuery()));
        signatureParameters.addAll(bodyParameters);
        signatureParameters.addAll(oauthParameters);
        signatureParameters.sort(Comparator.comparing((Parameter value) -> encode(value.name()))
                .thenComparing(value -> encode(value.value())));

        var normalizedParameters = String.join("&", signatureParameters.stream()
                .map(value -> encode(value.name()) + "=" + encode(value.value()))
                .toList());
        var baseString = method.toUpperCase(Locale.ROOT)
                + "&"
                + encode(normalizeBaseUri(uri))
                + "&"
                + encode(normalizedParameters);
        var signingKey = encode(credentials.consumerSecret())
                + "&"
                + encode(credentials.tokenSecret() == null ? "" : credentials.tokenSecret());
        var signature = sign(baseString, signingKey);

        oauthParameters.add(new Parameter("oauth_signature", signature));
        oauthParameters.sort(Comparator.comparing(Parameter::name));
        return "OAuth " + String.join(", ", oauthParameters.stream()
                .map(value -> encode(value.name()) + "=\"" + encode(value.value()) + "\"")
                .toList());
    }

    static String normalizeBaseUri(URI uri) {
        var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        var port = uri.getPort();
        var includePort = port != -1
                && !(scheme.equals("http") && port == 80)
                && !(scheme.equals("https") && port == 443);
        var path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return scheme + "://" + host + (includePort ? ":" + port : "") + path;
    }

    static String encode(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var encoded = new StringBuilder(bytes.length * 3);
        for (byte current : bytes) {
            var unsigned = current & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-'
                    || unsigned == '.'
                    || unsigned == '_'
                    || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((unsigned >>> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private static List<Parameter> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return List.of();
        }
        var parameters = new ArrayList<Parameter>();
        for (String part : rawQuery.split("&", -1)) {
            var separator = part.indexOf('=');
            var rawName = separator >= 0 ? part.substring(0, separator) : part;
            var rawValue = separator >= 0 ? part.substring(separator + 1) : "";
            parameters.add(new Parameter(decode(rawName), decode(rawValue)));
        }
        return parameters;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String sign(String baseString, String signingKey) {
        try {
            var mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
            return Base64.getEncoder().encodeToString(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("OAuth署名を生成できません。", exception);
        }
    }

    public record Parameter(String name, String value) {}

    public record Credentials(
            String consumerKey, String consumerSecret, String token, String tokenSecret) {}
}
