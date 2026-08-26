import { LockKeyhole, UnlockKeyhole } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";

interface VaultStatus {
  exists: boolean;
  unlocked: boolean;
  accountCount: number;
  unlockedAt: string | null;
}

interface AccountSummary {
  accountId: string;
  userId: string;
  username: string;
  displayName: string | null;
}

interface AccountVaultSetupProps {
  translation: Translation;
}

export function AccountVaultSetup({ translation }: AccountVaultSetupProps) {
  const [vaultStatus, setVaultStatus] = useState<VaultStatus | null>(null);
  const [accounts, setAccounts] = useState<AccountSummary[]>([]);
  const [passphrase, setPassphrase] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadAccounts = useCallback(async (signal?: AbortSignal) => {
    const response = await fetch("/api/v1/accounts/vault/accounts", { signal });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    setAccounts((await response.json()) as AccountSummary[]);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void loadStatus(controller.signal);
    return () => controller.abort();

    async function loadStatus(signal: AbortSignal) {
      try {
        const response = await fetch("/api/v1/accounts/vault/status", { signal });
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        const status = (await response.json()) as VaultStatus;
        setVaultStatus(status);
        if (status.unlocked) {
          await loadAccounts(signal);
        }
      } catch (loadError) {
        if (loadError instanceof DOMException && loadError.name === "AbortError") {
          return;
        }
        setError(translation.vaultOperationError);
      }
    }
  }, [translation.vaultOperationError, loadAccounts]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (vaultStatus === null) {
      return;
    }
    if (!vaultStatus.exists && passphrase !== confirmation) {
      setError(translation.passphraseMismatch);
      return;
    }
    setBusy(true);
    setError(null);
    const action = vaultStatus.exists ? "unlock" : "create";
    try {
      const response = await fetch(`/api/v1/accounts/vault/${action}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ passphrase }),
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const nextStatus: VaultStatus = {
        exists: true,
        unlocked: true,
        accountCount: vaultStatus.accountCount,
        unlockedAt: new Date().toISOString(),
      };
      setVaultStatus(nextStatus);
      setPassphrase("");
      setConfirmation("");
      await loadAccounts();
    } catch {
      setError(translation.vaultOperationError);
    } finally {
      setPassphrase("");
      setConfirmation("");
      setBusy(false);
    }
  };

  const lock = async () => {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/api/v1/accounts/vault/lock", { method: "POST" });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      setVaultStatus((current) =>
        current === null ? current : { ...current, unlocked: false, unlockedAt: null },
      );
      setAccounts([]);
    } catch {
      setError(translation.vaultOperationError);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="account-vault-setup" data-testid="account-vault-setup">
      <div className="vault-title-row">
        <h3>{translation.accountVault}</h3>
        {vaultStatus !== null && (
          <span className={vaultStatus.unlocked ? "ready" : "not-ready"}>
            {vaultStatus.unlocked ? (
              <UnlockKeyhole aria-hidden="true" size={15} />
            ) : (
              <LockKeyhole aria-hidden="true" size={15} />
            )}
            {vaultStatus.unlocked ? translation.vaultUnlocked : translation.vaultLocked}
          </span>
        )}
      </div>
      {vaultStatus?.unlocked ? (
        <div className="vault-unlocked-content">
          <h4>{translation.accounts}</h4>
          {accounts.length === 0 ? (
            <p>{translation.noAccounts}</p>
          ) : (
            <ul>
              {accounts.map((account) => (
                <li key={account.accountId}>
                  <strong>{account.displayName ?? account.username}</strong>
                  <span>@{account.username}</span>
                </li>
              ))}
            </ul>
          )}
          <button className="secondary-button" type="button" disabled={busy} onClick={lock}>
            {translation.lockVault}
          </button>
        </div>
      ) : (
        vaultStatus !== null && (
          <form className="vault-form" onSubmit={submit}>
            <p>{translation.passphraseRequirement}</p>
            <label>
              <span>{translation.vaultPassphrase}</span>
              <input
                required
                minLength={12}
                type="password"
                autoComplete="new-password"
                value={passphrase}
                onChange={(event) => setPassphrase(event.target.value)}
              />
            </label>
            {!vaultStatus.exists && (
              <label>
                <span>{translation.confirmPassphrase}</span>
                <input
                  required
                  minLength={12}
                  type="password"
                  autoComplete="new-password"
                  value={confirmation}
                  onChange={(event) => setConfirmation(event.target.value)}
                />
              </label>
            )}
            <button className="primary-button" type="submit" disabled={busy}>
              {busy
                ? translation.saving
                : vaultStatus.exists
                  ? translation.unlockVault
                  : translation.createVault}
            </button>
          </form>
        )
      )}
      {error !== null && <p className="setup-error">{error}</p>}
    </section>
  );
}
