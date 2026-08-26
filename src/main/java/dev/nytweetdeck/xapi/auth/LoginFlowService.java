package dev.nytweetdeck.xapi.auth;

import dev.nytweetdeck.account.vault.AccountSecrets;
import dev.nytweetdeck.account.vault.AccountVaultSessionManager;
import dev.nytweetdeck.account.vault.AccountVaultSessionManager.AccountSummary;
import dev.nytweetdeck.xapi.auth.ocf.OcfFlow;
import dev.nytweetdeck.xapi.auth.ocf.OcfLoginClient;
import dev.nytweetdeck.xapi.auth.ocf.OcfSubtask;
import dev.nytweetdeck.xapi.auth.ocf.OcfSubtaskInputFactory;
import dev.nytweetdeck.xapi.device.AndroidDeviceProfileStore;
import dev.nytweetdeck.xapi.profile.AndroidApiProfileService;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class LoginFlowService {

    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(15);

    private final GuestAuthenticationClient guestAuthenticationClient;
    private final OcfLoginClient loginClient;
    private final AndroidApiProfileService profileService;
    private final AndroidDeviceProfileStore deviceProfileStore;
    private final AccountVaultSessionManager vaultSessionManager;
    private final ConcurrentHashMap<String, LoginState> sessions = new ConcurrentHashMap<>();

    public LoginFlowService(
            GuestAuthenticationClient guestAuthenticationClient,
            OcfLoginClient loginClient,
            AndroidApiProfileService profileService,
            AndroidDeviceProfileStore deviceProfileStore,
            AccountVaultSessionManager vaultSessionManager) {
        this.guestAuthenticationClient = guestAuthenticationClient;
        this.loginClient = loginClient;
        this.profileService = profileService;
        this.deviceProfileStore = deviceProfileStore;
        this.vaultSessionManager = vaultSessionManager;
    }

    public LoginProgress start() {
        removeExpiredSessions();
        vaultSessionManager.accountSummaries();
        var identity = deviceProfileStore.require().toIdentity(profileService.profile());
        var guestSession = guestAuthenticationClient.activate(identity);
        var loginSession = loginClient.start(guestSession, identity);
        return storeOrComplete(UUID.randomUUID().toString(), loginSession);
    }

    public LoginProgress submit(
            String sessionId,
            String subtaskId,
            char[] value,
            List<String> choiceIds,
            String link) {
        removeExpiredSessions();
        var state = sessions.get(sessionId);
        if (state == null) {
            clear(value);
            throw new IllegalArgumentException("ログインセッションが見つからないか期限切れです。");
        }
        var subtask = state.session().flow().subtasks().stream()
                .filter(candidate -> candidate.id().equals(subtaskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ログイン入力項目が見つかりません。"));
        try {
            var submission = new OcfSubtaskInputFactory.Submission(
                    value == null ? null : new String(value), choiceIds, link);
            var identity = deviceProfileStore.require().toIdentity(profileService.profile());
            var updated = loginClient.submit(state.session(), subtask, submission, identity);
            return storeOrComplete(sessionId, updated);
        } finally {
            clear(value);
        }
    }

    public void cancel(String sessionId) {
        sessions.remove(sessionId);
    }

    private LoginProgress storeOrComplete(
            String sessionId, OcfLoginClient.LoginSession loginSession) {
        var account = loginSession.flow().account();
        if (account != null) {
            sessions.remove(sessionId);
            var secrets = new AccountSecrets(
                    account.userId(),
                    account.userId(),
                    account.username(),
                    account.displayName(),
                    account.oauthToken(),
                    account.oauthTokenSecret());
            vaultSessionManager.addOrReplace(secrets);
            return new LoginProgress(
                    null,
                    true,
                    List.of(),
                    new AccountSummary(
                            secrets.accountId(),
                            secrets.userId(),
                            secrets.username(),
                            secrets.displayName()));
        }
        sessions.put(sessionId, new LoginState(loginSession, Instant.now()));
        return new LoginProgress(
                sessionId,
                false,
                loginSession.flow().subtasks().stream().map(PublicSubtask::from).toList(),
                null);
    }

    private void removeExpiredSessions() {
        var cutoff = Instant.now().minus(SESSION_LIFETIME);
        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccess().isBefore(cutoff));
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private record LoginState(OcfLoginClient.LoginSession session, Instant lastAccess) {}

    public record LoginProgress(
            String sessionId,
            boolean complete,
            List<PublicSubtask> subtasks,
            AccountSummary account) {
        public LoginProgress {
            subtasks = List.copyOf(subtasks);
        }
    }

    public record PublicSubtask(
            String id,
            String type,
            String prompt,
            String hint,
            String nextLink,
            List<OcfSubtask.Choice> choices) {

        private static PublicSubtask from(OcfSubtask subtask) {
            return new PublicSubtask(
                    subtask.id(),
                    subtask.type().name(),
                    subtask.prompt(),
                    subtask.hint(),
                    subtask.nextLink(),
                    subtask.choices());
        }
    }
}
