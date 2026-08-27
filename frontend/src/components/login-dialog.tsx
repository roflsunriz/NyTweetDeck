import { useEffect, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";
import { Modal } from "./modal";

interface BrowserLoginStatus {
  sessionId: string;
  phase: "STARTING" | "WAITING_USER" | "CAPTURING" | "COMPLETE" | "FAILED" | "CANCELLED";
  account: { accountId: string } | null;
  errorCode: string | null;
}

interface LoginDialogProps {
  translation: Translation;
  onComplete: (accountId: string) => void;
  onClose: () => void;
}

export function LoginDialog({ translation, onComplete, onClose }: LoginDialogProps) {
  const [status, setStatus] = useState<BrowserLoginStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  const requestId = useRef(crypto.randomUUID());

  useEffect(() => {
    const controller = new AbortController();
    let timeout: ReturnType<typeof setTimeout> | undefined;

    const handleError = (loadError: unknown) => {
      if (!(loadError instanceof DOMException && loadError.name === "AbortError")) {
        setError(translation.loginFailed);
      }
    };

    const poll = async (sessionId: string): Promise<void> => {
      const response = await fetch(`/api/v1/login/browser/${encodeURIComponent(sessionId)}`, {
        signal: controller.signal,
      });
      const next = await readStatus(response);
      setStatus(next);
      if (["STARTING", "WAITING_USER", "CAPTURING"].includes(next.phase)) {
        timeout = setTimeout(() => void poll(sessionId).catch(handleError), 750);
      }
    };

    void fetch(
      `/api/v1/login/browser/start?requestId=${encodeURIComponent(requestId.current)}&attempt=${retryKey}`,
      {
        method: "POST",
        signal: controller.signal,
      },
    )
      .then(readStatus)
      .then((initial) => {
        setStatus(initial);
        return poll(initial.sessionId);
      })
      .catch(handleError);

    return () => {
      controller.abort();
      if (timeout !== undefined) clearTimeout(timeout);
    };
  }, [retryKey, translation.loginFailed]);

  useEffect(() => {
    if (status?.phase === "COMPLETE" && status.account !== null) {
      onComplete(status.account.accountId);
    } else if (status?.phase === "FAILED") {
      setError(
        status.errorCode === "BROWSER_CLOSED"
          ? translation.browserLoginBrowserClosed
          : translation.loginFailed,
      );
    }
  }, [onComplete, status, translation.browserLoginBrowserClosed, translation.loginFailed]);

  const close = () => {
    if (status !== null && ["STARTING", "WAITING_USER", "CAPTURING"].includes(status.phase)) {
      void fetch(`/api/v1/login/browser/${encodeURIComponent(status.sessionId)}`, {
        method: "DELETE",
      });
    }
    onClose();
  };

  const retry = () => {
    if (status !== null) {
      void fetch(`/api/v1/login/browser/${encodeURIComponent(status.sessionId)}`, {
        method: "DELETE",
      });
    }
    setStatus(null);
    setError(null);
    setRetryKey((current) => current + 1);
  };

  const capture = async () => {
    if (status?.phase !== "WAITING_USER" && status?.phase !== "FAILED") return;
    setError(null);
    try {
      const response = await fetch(
        `/api/v1/login/browser/${encodeURIComponent(status.sessionId)}/capture`,
        { method: "POST" },
      );
      setStatus(await readStatus(response));
    } catch {
      setError(translation.loginFailed);
    }
  };

  return (
    <Modal title={translation.loginAccount} closeLabel={translation.close} onClose={close}>
      <div className="login-flow" data-testid="browser-login-flow">
        {error === null && status?.phase === "WAITING_USER" ? (
          <>
            <p className="login-prompt">{translation.browserLoginInstructions}</p>
            <button className="primary-button" type="button" onClick={capture}>
              {translation.browserLoginCapture}
            </button>
          </>
        ) : error === null ? (
          <>
            <p className="login-prompt">{translation.browserLoginCapturing}</p>
            <p className="modal-description">{translation.loading}</p>
          </>
        ) : (
          <>
            <p className="setup-error">{error}</p>
            <button
              className="primary-button"
              type="button"
              onClick={
                status?.phase === "FAILED" && status.errorCode !== "BROWSER_CLOSED"
                  ? capture
                  : retry
              }
            >
              {translation.continueLogin}
            </button>
          </>
        )}
      </div>
    </Modal>
  );
}

async function readStatus(response: Response): Promise<BrowserLoginStatus> {
  if (!response.ok) throw new LoginHttpError(response.status);
  return (await response.json()) as BrowserLoginStatus;
}

class LoginHttpError extends Error {
  constructor(readonly status: number) {
    super(`HTTP ${status}`);
  }
}
