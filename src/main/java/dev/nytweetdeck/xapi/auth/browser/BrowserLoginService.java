package dev.nytweetdeck.xapi.auth.browser;

import dev.nytweetdeck.account.AccountStore;
import dev.nytweetdeck.account.AccountStore.AccountSummary;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class BrowserLoginService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserLoginService.class);
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(10);
    private static final URI LOGIN_URI = URI.create("https://x.com/i/flow/login");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("@([A-Za-z0-9_]{1,15})");
    private static final AtomicLong WORKER_SEQUENCE = new AtomicLong();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AccountStore accountStore;
    private final WebBearerTokenProvider bearerTokenProvider;
    private final WebAccountVerifier accountVerifier;
    private final Path sessionRoot;
    private final String configuredChromePath;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(
                runnable, "nytweetdeck-browser-login-" + WORKER_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, LoginState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> requestSessions = new ConcurrentHashMap<>();

    public BrowserLoginService(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            AccountStore accountStore,
            WebBearerTokenProvider bearerTokenProvider,
            WebAccountVerifier accountVerifier,
            @Value("${nytweetdeck.login.browser-session-path:.local/browser-login}") String sessionRoot,
            @Value("${nytweetdeck.login.chrome-path:}") String configuredChromePath) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.accountStore = accountStore;
        this.bearerTokenProvider = bearerTokenProvider;
        this.accountVerifier = accountVerifier;
        this.sessionRoot = Path.of(sessionRoot).toAbsolutePath().normalize();
        this.configuredChromePath = configuredChromePath;
    }

    public synchronized BrowserLoginStatus start(String requestKey) {
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9:-]{1,100}")) {
            throw new IllegalArgumentException("ブラウザログイン要求IDが不正です。");
        }
        removeExpiredSessions();
        var existingSessionId = requestSessions.get(requestKey);
        if (existingSessionId != null) {
            var existingState = sessions.get(existingSessionId);
            if (existingState != null) {
                return existingState.status();
            }
        }
        var sessionId = UUID.randomUUID().toString();
        var state = new LoginState(
                sessionId,
                Instant.now(),
                sessionRoot.resolve(sessionId).normalize(),
                availableLoopbackPort());
        sessions.put(sessionId, state);
        requestSessions.put(requestKey, sessionId);
        try {
            Files.createDirectories(state.profilePath);
            state.process = launchChrome(state.profilePath, state.debugPort);
            state.phase = Phase.WAITING_USER;
            return state.status();
        } catch (Exception exception) {
            sessions.remove(sessionId);
            requestSessions.remove(requestKey, sessionId);
            state.stopProcess();
            deleteSessionProfile(state.profilePath);
            throw new IllegalStateException("X公式ログイン画面を開けません。", exception);
        }
    }

    public BrowserLoginStatus status(String sessionId) {
        removeExpiredSessions();
        var state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("ブラウザログインセッションが見つからないか期限切れです。");
        }
        return state.status();
    }

    public void cancel(String sessionId) {
        var state = sessions.remove(sessionId);
        requestSessions.entrySet().removeIf(entry -> entry.getValue().equals(sessionId));
        if (state != null) {
            state.cancel();
            deleteSessionProfile(state.profilePath);
        }
    }

    public BrowserLoginStatus capture(String sessionId) {
        var state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("ブラウザログインセッションが見つからないか期限切れです。");
        }
        synchronized (state) {
            if (state.phase != Phase.WAITING_USER && state.phase != Phase.FAILED) {
                return state.status();
            }
            if (state.process == null || !state.process.isAlive()) {
                state.fail("BROWSER_CLOSED");
                return state.status();
            }
            state.errorCode = null;
            state.phase = Phase.CAPTURING;
        }
        executor.submit(() -> captureSession(state));
        return state.status();
    }

    private void captureSession(LoginState state) {
        ChromeCdpClient cdp = null;
        var stage = "CONNECT_BROWSER";
        try {
            var endpoint = awaitPageEndpoint(state.debugPort, state);
            cdp = ChromeCdpClient.connect(httpClient, objectMapper, endpoint);
            stage = "READ_SESSION";
            var cookies = awaitLoginCookies(cdp, state);
            var identity = readBrowserIdentity(cdp);
            stage = "PREPARE_ACCOUNT";
            var account = accountVerifier.verify(cookies, bearerTokenProvider.require(), identity);
            stage = "SAVE_ACCOUNT";
            accountStore.addOrReplace(account);
            state.complete(new AccountSummary(
                    account.accountId(), account.userId(), account.username(), account.displayName()));
        } catch (Exception exception) {
            if (!state.cancelled) {
                var code = errorCode(exception);
                state.fail(code);
                LOGGER.warn(
                        "ブラウザログインの取り込みに失敗しました: sessionId={}, stage={}, errorCode={}, cause={}",
                        state.sessionId,
                        stage,
                        code,
                        exception.getClass().getSimpleName());
            }
        } finally {
            if (cdp != null) {
                cdp.close();
            }
            if (state.phase == Phase.COMPLETE || state.cancelled) {
                state.stopProcess();
                deleteSessionProfile(state.profilePath);
            }
        }
    }

    private WebAccountVerifier.BrowserIdentity readBrowserIdentity(ChromeCdpClient cdp) {
        var result = cdp.call("Runtime.evaluate", Map.of(
                "expression",
                """
                (() => {
                  const node = document.querySelector('[data-testid="SideNav_AccountSwitcher_Button"]');
                  const image = node?.querySelector('img');
                  return { text: node?.textContent ?? '', alt: image?.getAttribute('alt') ?? '' };
                })()
                """,
                "returnByValue",
                true));
        var value = result.path("result").path("value");
        var text = value.path("text").asString("");
        var matcher = USERNAME_PATTERN.matcher(text);
        var username = matcher.find() ? matcher.group(1) : "";
        var displayName = value.path("alt").asString("").strip();
        if (displayName.isBlank() && !username.isBlank()) {
            var marker = text.indexOf("@" + username);
            if (marker > 0) {
                displayName = text.substring(0, marker).strip();
            }
        }
        return new WebAccountVerifier.BrowserIdentity(username, displayName);
    }

    private Process launchChrome(Path profilePath, int debugPort) throws IOException {
        var command = new ArrayList<String>();
        command.add(requireChrome().toString());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--user-data-dir=" + profilePath);
        command.add("--remote-debugging-port=" + debugPort);
        command.add("--remote-debugging-address=127.0.0.1");
        command.add("--disable-background-mode");
        command.add("--new-window");
        command.add(LOGIN_URI.toString());
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static int availableLoopbackPort() {
        try (var socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Chrome用のローカル接続を準備できません。", exception);
        }
    }

    private URI awaitPageEndpoint(int port, LoginState state) throws Exception {
        var deadline = Instant.now().plusSeconds(30);
        var uri = URI.create("http://127.0.0.1:" + port + "/json/list");
        while (Instant.now().isBefore(deadline)) {
            state.ensureActive();
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    for (var target : objectMapper.readTree(response.body())) {
                        if ("page".equals(target.path("type").asString(""))
                                && target.path("url").asString("").startsWith("https://x.com/")) {
                            return URI.create(target.path("webSocketDebuggerUrl").asString(""));
                        }
                    }
                }
            } catch (IOException ignored) {
                // Chromeが待受を開始するまで再試行する。
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Xログイン画面へ接続できません。");
    }

    private Map<String, String> awaitLoginCookies(ChromeCdpClient cdp, LoginState state)
            throws Exception {
        var deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            state.ensureActive();
            var result = cdp.call("Network.getAllCookies");
            var cookies = new LinkedHashMap<String, String>();
            for (var cookie : result.path("cookies")) {
                var domain = cookie.path("domain").asString("");
                if (domain.equals("x.com") || domain.endsWith(".x.com")) {
                    cookies.put(
                            cookie.path("name").asString(""),
                            cookie.path("value").asString(""));
                }
            }
            if (cookies.containsKey("auth_token")
                    && cookies.containsKey("ct0")
                    && cookies.containsKey("twid")) {
                return Map.copyOf(cookies);
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Xログイン済みセッションを確認できませんでした。");
    }

    private Path requireChrome() {
        var candidates = new ArrayList<Path>();
        if (!configuredChromePath.isBlank()) {
            candidates.add(Path.of(configuredChromePath));
        }
        addCandidate(candidates, System.getenv("PROGRAMFILES"), "Google/Chrome/Application/chrome.exe");
        addCandidate(candidates, System.getenv("PROGRAMFILES(X86)"), "Google/Chrome/Application/chrome.exe");
        addCandidate(candidates, System.getenv("LOCALAPPDATA"), "Google/Chrome/Application/chrome.exe");
        candidates.add(Path.of("/usr/bin/google-chrome"));
        candidates.add(Path.of("/usr/bin/chromium"));
        candidates.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Google Chromeが見つかりません。"));
    }

    private static void addCandidate(List<Path> candidates, String root, String suffix) {
        if (root != null && !root.isBlank()) {
            candidates.add(Path.of(root).resolve(suffix));
        }
    }

    private void removeExpiredSessions() {
        var cutoff = Instant.now().minus(LOGIN_TIMEOUT).minusSeconds(30);
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().createdAt.isBefore(cutoff)) {
                entry.getValue().cancel();
                deleteSessionProfile(entry.getValue().profilePath);
                return true;
            }
            return false;
        });
        requestSessions.entrySet().removeIf(entry -> !sessions.containsKey(entry.getValue()));
    }

    private void deleteSessionProfile(Path profilePath) {
        var normalized = profilePath.toAbsolutePath().normalize();
        if (!normalized.startsWith(sessionRoot) || normalized.equals(sessionRoot)) {
            return;
        }
        try (var paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Chrome終了直後の一時ロックは次回起動時の期限切れ掃除へ委ねる。
                }
            });
        } catch (IOException ignored) {
            // 一時プロファイルの削除失敗はログイン結果を壊さない。
        }
    }

    private static String errorCode(Exception exception) {
        var message = exception.getMessage();
        if (message != null && message.contains("Chrome")) {
            if (message.contains("ログイン完了前に終了")) {
                return "BROWSER_CLOSED";
            }
            return "CHROME_UNAVAILABLE";
        }
        if (message != null && message.contains("確認できません")) {
            return "NOT_LOGGED_IN";
        }
        return "LOGIN_FAILED";
    }

    @PreDestroy
    void shutdown() {
        sessions.values().forEach(LoginState::cancel);
        executor.shutdownNow();
    }

    public enum Phase {
        STARTING,
        WAITING_USER,
        CAPTURING,
        COMPLETE,
        FAILED,
        CANCELLED
    }

    public record BrowserLoginStatus(
            String sessionId, Phase phase, AccountSummary account, String errorCode) {}

    private static final class LoginState {
        private final String sessionId;
        private final Instant createdAt;
        private final Path profilePath;
        private final int debugPort;
        private volatile Phase phase = Phase.STARTING;
        private volatile AccountSummary account;
        private volatile String errorCode;
        private volatile Process process;
        private volatile boolean cancelled;

        private LoginState(String sessionId, Instant createdAt, Path profilePath, int debugPort) {
            this.sessionId = sessionId;
            this.createdAt = createdAt;
            this.profilePath = profilePath;
            this.debugPort = debugPort;
        }

        private BrowserLoginStatus status() {
            return new BrowserLoginStatus(sessionId, phase, account, errorCode);
        }

        private void complete(AccountSummary value) {
            account = value;
            phase = Phase.COMPLETE;
        }

        private void fail(String value) {
            errorCode = value;
            phase = Phase.FAILED;
        }

        private void cancel() {
            cancelled = true;
            phase = Phase.CANCELLED;
            stopProcess();
        }

        private void ensureActive() {
            if (cancelled) {
                throw new IllegalStateException("ログインがキャンセルされました。");
            }
            var current = process;
            if (current != null && !current.isAlive()) {
                throw new IllegalStateException("Chromeがログイン完了前に終了しました。");
            }
        }

        private void stopProcess() {
            var current = process;
            if (current != null && current.isAlive()) {
                current.destroy();
                try {
                    if (!current.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        current.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    current.destroyForcibly();
                }
            }
        }
    }
}
