import { CircleUserRound, X } from "lucide-react";
import { useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import { useMediaQuery } from "../model/use-media-query";

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
  const compactPresentation = useMediaQuery("(max-width: 599px)");
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
    <div className="account-switcher-layer">
      <section
        className="account-switcher-surface"
        role={compactPresentation ? "dialog" : "group"}
        aria-label={translation.selectAccount}
        data-presentation={compactPresentation ? "phone-dialog" : "wide-group"}
      >
        <header className="modal-header">
          <h2>{translation.selectAccount}</h2>
          <button
            className="icon-button"
            type="button"
            aria-label={translation.close}
            onClick={onClose}
          >
            <X aria-hidden="true" size={20} />
          </button>
        </header>
        <div className="account-switcher-list">
          {accounts === null ? (
            <p>{translation.loading}</p>
          ) : accounts.length === 0 ? (
            <p>{translation.noAccounts}</p>
          ) : (
            accounts.map((account) => (
              <button
                type="button"
                role={compactPresentation ? undefined : "menuitem"}
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
        <button
          className="primary-button account-login-button"
          type="button"
          role={compactPresentation ? undefined : "menuitem"}
          onClick={onLogin}
        >
          {translation.loginAccount}
        </button>
      </section>
    </div>
  );
}
