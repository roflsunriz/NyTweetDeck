package dev.nytweetdeck.account.vault;

import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/vault")
public class AccountVaultController {

    private final AccountVaultSessionManager sessionManager;

    public AccountVaultController(AccountVaultSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @GetMapping("/status")
    public AccountVaultSessionManager.VaultStatus status() {
        return sessionManager.status();
    }

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void create(@RequestBody PassphraseRequest request) {
        useAndClear(request.passphrase(), sessionManager::create);
    }

    @PostMapping("/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@RequestBody PassphraseRequest request) {
        useAndClear(request.passphrase(), sessionManager::unlock);
    }

    @PostMapping("/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lock() {
        sessionManager.lock();
    }

    @GetMapping("/accounts")
    public List<AccountVaultSessionManager.AccountSummary> accounts() {
        return sessionManager.accountSummaries();
    }

    private static void useAndClear(char[] passphrase, PassphraseAction action) {
        if (passphrase == null) {
            throw new IllegalArgumentException("Vaultパスフレーズがありません。");
        }
        try {
            action.apply(passphrase);
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    public record PassphraseRequest(char[] passphrase) {}

    @FunctionalInterface
    private interface PassphraseAction {
        void apply(char[] passphrase);
    }
}
