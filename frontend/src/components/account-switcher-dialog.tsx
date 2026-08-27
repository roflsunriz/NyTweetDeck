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
  onClose: () => void;
}

export function AccountSwitcherDialog({
  translation,
  activeAccountId,
  onSelect,
  onLogin,
  onClose,
}: AccountSwitcherDialogProps) {
  const [accounts, setAccounts] = useState<AccountSummary[] | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/v1/accounts", { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        return response.json();
      })
      .then((value) => setAccounts(value as AccountSummary[]))
      .catch((error) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
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
          <p>{translation.noAccounts}</p>
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
      <button className="primary-button account-login-button" type="button" onClick={onLogin}>
        {translation.loginAccount}
      </button>
    </Modal>
  );
}
