package dev.nytweetdeck.xapi.auth.browser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ChromeCdpClient implements WebSocket.Listener, AutoCloseable {

    private final ObjectMapper objectMapper;
    private final AtomicLong nextId = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<>();
    private final StringBuilder incoming = new StringBuilder();
    private volatile WebSocket webSocket;

    private ChromeCdpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    static ChromeCdpClient connect(HttpClient httpClient, ObjectMapper objectMapper, URI endpoint) {
        var client = new ChromeCdpClient(objectMapper);
        client.webSocket = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(endpoint, client)
                .join();
        return client;
    }

    JsonNode call(String method) {
        return call(method, Map.of());
    }

    JsonNode call(String method, Map<String, ?> parameters) {
        var id = nextId.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        try {
            var message = objectMapper.writeValueAsString(Map.of(
                    "id", id, "method", method, "params", parameters));
            webSocket.sendText(message, true).join();
            return future.orTimeout(10, TimeUnit.SECONDS).join();
        } catch (RuntimeException exception) {
            pending.remove(id);
            throw exception;
        } catch (Exception exception) {
            pending.remove(id);
            throw new IllegalStateException("Chromeとの通信要求を作成できません。", exception);
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        synchronized (incoming) {
            incoming.append(data);
            if (last) {
                accept(incoming.toString());
                incoming.setLength(0);
            }
        }
        socket.request(1);
        return null;
    }

    @Override
    public void onOpen(WebSocket socket) {
        socket.request(1);
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        pending.values().forEach(future -> future.completeExceptionally(error));
        pending.clear();
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
        var error = new IllegalStateException("Chromeとの接続が終了しました。");
        pending.values().forEach(future -> future.completeExceptionally(error));
        pending.clear();
        return null;
    }

    private void accept(String rawMessage) {
        try {
            var message = objectMapper.readTree(rawMessage);
            var idNode = message.get("id");
            if (idNode == null) {
                return;
            }
            var future = pending.remove(idNode.asLong());
            if (future == null) {
                return;
            }
            var error = message.get("error");
            if (error != null) {
                future.completeExceptionally(new IllegalStateException("Chrome操作に失敗しました。"));
            } else {
                future.complete(message.path("result"));
            }
        } catch (Exception exception) {
            pending.values().forEach(future -> future.completeExceptionally(exception));
            pending.clear();
        }
    }

    @Override
    public void close() {
        var socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }
}
