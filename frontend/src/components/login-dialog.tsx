import { type FormEvent, useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import { Modal } from "./modal";

interface LoginSubtask {
  id: string;
  type: string;
  prompt: string | null;
  hint: string | null;
  nextLink: string;
  choices: Array<{ id: string; label: string }>;
}

interface LoginProgress {
  sessionId: string | null;
  complete: boolean;
  subtasks: LoginSubtask[];
  account: { accountId: string } | null;
}

interface LoginDialogProps {
  translation: Translation;
  onComplete: (accountId: string) => void;
  onClose: () => void;
}

export function LoginDialog({ translation, onComplete, onClose }: LoginDialogProps) {
  const [progress, setProgress] = useState<LoginProgress | null>(null);
  const [value, setValue] = useState("");
  const [choiceId, setChoiceId] = useState("");
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const subtask = progress?.subtasks[0] ?? null;

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/v1/login/start", { method: "POST", signal: controller.signal })
      .then(readProgress)
      .then(setProgress)
      .catch((loadError) => {
        if (!(loadError instanceof DOMException && loadError.name === "AbortError")) {
          setError(translation.loginFailed);
        }
      })
      .finally(() => setBusy(false));
    return () => controller.abort();
  }, [translation.loginFailed]);

  useEffect(() => {
    if (progress?.complete && progress.account !== null) {
      onComplete(progress.account.accountId);
    }
  }, [onComplete, progress]);

  const close = () => {
    if (progress?.sessionId !== null && progress?.sessionId !== undefined) {
      void fetch(`/api/v1/login/${encodeURIComponent(progress.sessionId)}`, { method: "DELETE" });
    }
    onClose();
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (progress?.sessionId === null || progress?.sessionId === undefined || subtask === null) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/v1/login/${encodeURIComponent(progress.sessionId)}/submit`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            subtaskId: subtask.id,
            value: value.length === 0 ? null : value,
            choiceIds: choiceId.length === 0 ? [] : [choiceId],
            link: subtask.nextLink,
          }),
        },
      );
      const next = await readProgress(response);
      setProgress(next);
      setValue("");
      setChoiceId("");
    } catch {
      setError(translation.loginFailed);
    } finally {
      setBusy(false);
    }
  };

  const unsupported = subtask !== null && !isSupportedType(subtask.type);
  return (
    <Modal title={translation.loginAccount} closeLabel={translation.close} onClose={close}>
      <div className="login-flow">
        {busy && subtask === null ? (
          <p>{translation.loading}</p>
        ) : subtask === null ? (
          <p className="setup-error">{error ?? translation.loginFailed}</p>
        ) : (
          <form onSubmit={submit}>
            <p className="login-prompt">{subtask.prompt ?? translation.loginAccount}</p>
            {subtask.hint !== null && <p className="modal-description">{subtask.hint}</p>}
            {subtask.type === "CHOICE" ? (
              <select
                required
                aria-label={subtask.prompt ?? translation.loginAccount}
                value={choiceId}
                onChange={(event) => setChoiceId(event.target.value)}
              >
                <option value="">{translation.selectLoginChoice}</option>
                {subtask.choices.map((choice) => (
                  <option key={choice.id} value={choice.id}>
                    {choice.label}
                  </option>
                ))}
              </select>
            ) : needsValue(subtask.type) ? (
              <input
                required
                maxLength={320}
                type={subtask.type === "PASSWORD" ? "password" : "text"}
                autoComplete={subtask.type === "PASSWORD" ? "current-password" : "username"}
                aria-label={subtask.prompt ?? translation.loginAccount}
                value={value}
                onChange={(event) => setValue(event.target.value)}
              />
            ) : null}
            {unsupported && <p className="setup-error">{translation.unsupportedLoginStep}</p>}
            {error !== null && <p className="setup-error">{error}</p>}
            <button className="primary-button" type="submit" disabled={busy || unsupported}>
              {busy ? translation.loading : translation.continueLogin}
            </button>
          </form>
        )}
      </div>
    </Modal>
  );
}

async function readProgress(response: Response): Promise<LoginProgress> {
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return (await response.json()) as LoginProgress;
}

function needsValue(type: string): boolean {
  return ["TEXT", "USERNAME", "PASSWORD", "EMAIL_CODE", "PHONE_CODE"].includes(type);
}

function isSupportedType(type: string): boolean {
  return needsValue(type) || ["CHOICE", "CONFIRM", "COMPLETE"].includes(type);
}
