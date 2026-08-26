import { CircleUserRound } from "lucide-react";
import { useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import { Modal } from "./modal";

interface AccountSummary {
  accountId: string;
  userId: string;
  username: string;
  displayName: string | null;
}

interface AccountSwitcherDialogProps {
  translation: Translation;
  activeAccountId: string | null;
  onSelect: (accountId: string) => void;
  onLogin: () => void;
  onSetup: () => void;
  onClose: () => void;
}

export function AccountSwitcherDialog({
  translation,
  activeAccountId,
  onSelect,
  onLogin,
  onSetup,
  onClose,
}: AccountSwitcherDialogProps) {
  const [accounts, setAccounts] = useState<AccountSummary[] | null>(null);
  const [vaultUnlocked, setVaultUnlocked] = useState<boolean | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/v1/accounts/vault/status", { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        const status = (await response.json()) as { unlocked: boolean };
        setVaultUnlocked(status.unlocked);
        if (!status.unlocked) {
          return [];
        }
        const accountsResponse = await fetch("/api/v1/accounts/vault/accounts", {
          signal: controller.signal,
        });
        return accountsResponse.ok ? accountsResponse.json() : [];
      })
      .then((value) => setAccounts(value as AccountSummary[]))
      .catch((error) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
          setVaultUnlocked(false);
          setAccounts([]);
        }
      });
    return () => controller.abort();
  }, []);

  return (
    <Modal title={translation.selectAccount} closeLabel={translation.close} onClose={onClose}>
      <div className="account-switcher-list">
        {accounts === null ? (
          <p>{translation.loading}</p>
        ) : accounts.length === 0 ? (
          <p>{translation.noUnlockedAccounts}</p>
        ) : (
          accounts.map((account) => (
            <button
              type="button"
              key={account.accountId}
              className={account.accountId === activeAccountId ? "active" : ""}
              onClick={() => onSelect(account.accountId)}
            >
              <CircleUserRound aria-hidden="true" size={25} />
              <span>
                <strong>{account.displayName ?? account.username}</strong>
                <small>@{account.username}</small>
              </span>
              {account.accountId === activeAccountId && <em>{translation.activeAccount}</em>}
            </button>
          ))
        )}
      </div>
      {vaultUnlocked !== null && (
        <button
          className="primary-button account-login-button"
          type="button"
          onClick={vaultUnlocked ? onLogin : onSetup}
        >
          {vaultUnlocked ? translation.loginAccount : translation.openVaultSettings}
        </button>
      )}
    </Modal>
  );
}
