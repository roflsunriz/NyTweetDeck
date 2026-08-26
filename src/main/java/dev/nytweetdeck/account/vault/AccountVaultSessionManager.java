package dev.nytweetdeck.account.vault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

@Service
public class AccountVaultSessionManager {

    private final EncryptedAccountVault vault;
    private final ApplicationEventPublisher eventPublisher;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<AccountSecrets> accounts = List.of();
    private char[] passphrase;
    private Instant unlockedAt;

    public AccountVaultSessionManager(EncryptedAccountVault vault) {
        this(vault, ignored -> {});
    }

    @Autowired
    public AccountVaultSessionManager(
            EncryptedAccountVault vault, ApplicationEventPublisher eventPublisher) {
        this.vault = vault;
        this.eventPublisher = eventPublisher;
    }

    public VaultStatus status() {
        lock.readLock().lock();
        try {
            return new VaultStatus(
                    vault.exists(), passphrase != null, accounts.size(), unlockedAt);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void create(char[] requestedPassphrase) {
        lock.writeLock().lock();
        try {
            if (vault.exists()) {
                throw new VaultException("アカウントVaultはすでに存在します。");
            }
            vault.save(List.of(), requestedPassphrase);
            replaceSession(List.of(), requestedPassphrase);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void unlock(char[] requestedPassphrase) {
        lock.writeLock().lock();
        try {
            replaceSession(vault.load(requestedPassphrase), requestedPassphrase);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void lock() {
        lock.writeLock().lock();
        try {
            clearPassphrase();
            accounts = List.of();
            unlockedAt = null;
        } finally {
            lock.writeLock().unlock();
        }
        eventPublisher.publishEvent(new VaultLockedEvent());
    }

    public List<AccountSummary> accountSummaries() {
        lock.readLock().lock();
        try {
            requireUnlocked();
            return accounts.stream()
                    .map(account -> new AccountSummary(
                            account.accountId(),
                            account.userId(),
                            account.username(),
                            account.displayName()))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void addOrReplace(AccountSecrets account) {
        lock.writeLock().lock();
        try {
            requireUnlocked();
            var updated = new ArrayList<AccountSecrets>();
            for (AccountSecrets existing : accounts) {
                if (!existing.accountId().equals(account.accountId())) {
                    updated.add(existing);
                }
            }
            updated.add(account);
            vault.save(updated, passphrase);
            accounts = List.copyOf(updated);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public AccountSecrets requireAccount(String accountId) {
        lock.readLock().lock();
        try {
            requireUnlocked();
            return accounts.stream()
                    .filter(account -> account.accountId().equals(accountId))
                    .findFirst()
                    .orElseThrow(() -> new VaultException("指定したアカウントがありません。"));
        } finally {
            lock.readLock().unlock();
        }
    }

    private void replaceSession(List<AccountSecrets> loadedAccounts, char[] requestedPassphrase) {
        clearPassphrase();
        passphrase = Arrays.copyOf(requestedPassphrase, requestedPassphrase.length);
        accounts = List.copyOf(loadedAccounts);
        unlockedAt = Instant.now();
    }

    private void requireUnlocked() {
        if (passphrase == null) {
            throw new VaultException("アカウントVaultはロックされています。");
        }
    }

    private void clearPassphrase() {
        if (passphrase != null) {
            Arrays.fill(passphrase, '\0');
            passphrase = null;
        }
    }

    public record VaultStatus(
            boolean exists, boolean unlocked, int accountCount, Instant unlockedAt) {}

    public record AccountSummary(
            String accountId, String userId, String username, String displayName) {}
}
