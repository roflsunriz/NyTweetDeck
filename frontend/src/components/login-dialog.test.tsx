import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { LoginDialog } from "./login-dialog";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("X公式ブラウザログイン", () => {
  test("公式ログイン完了後に暗号化保存されたアカウントを選択する", async () => {
    let polls = 0;
    let captured = false;
    globalThis.fetch = (async (input, init) => {
      if (String(input).includes("/start")) {
        return Response.json({
          sessionId: "session-1",
          phase: "WAITING_USER",
          account: null,
          errorCode: null,
        });
      }
      if (String(input).endsWith("/capture") && init?.method === "POST") {
        captured = true;
        polls = 0;
        return Response.json({
          sessionId: "session-1",
          phase: "CAPTURING",
          account: null,
          errorCode: null,
        });
      }
      polls += 1;
      if (!captured) {
        return Response.json({
          sessionId: "session-1",
          phase: "WAITING_USER",
          account: null,
          errorCode: null,
        });
      }
      return Response.json({
        sessionId: "session-1",
        phase: polls >= 2 ? "COMPLETE" : "WAITING_USER",
        account: polls >= 2 ? { accountId: "42" } : null,
        errorCode: null,
      });
    }) as typeof fetch;
    let selected = "";
    const user = userEvent.setup();
    render(
      <LoginDialog
        translation={translate("ja")}
        onComplete={(accountId) => {
          selected = accountId;
        }}
        onClose={() => undefined}
      />,
    );

    expect(await screen.findByTestId("browser-login-flow")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "Xへのログインが完了しました" }));
    await waitFor(() => expect(selected).toBe("42"), { timeout: 3_000 });
  });

  test("Vault未解除時は解除手順を表示する", async () => {
    globalThis.fetch = (async () => new Response(null, { status: 423 })) as unknown as typeof fetch;
    render(
      <LoginDialog
        translation={translate("ja")}
        onComplete={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(await screen.findByText(/先に設定でアカウントVault/)).toBeDefined();
  });

  test("専用Chromeが閉じられた場合は再ログイン手順を表示する", async () => {
    globalThis.fetch = (async (input, init) => {
      if (String(input).endsWith("/capture") && init?.method === "POST") {
        return Response.json({
          sessionId: "session-2",
          phase: "FAILED",
          account: null,
          errorCode: "BROWSER_CLOSED",
        });
      }
      return Response.json({
        sessionId: "session-2",
        phase: "WAITING_USER",
        account: null,
        errorCode: null,
      });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(
      <LoginDialog
        translation={translate("ja")}
        onComplete={() => undefined}
        onClose={() => undefined}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "Xへのログインが完了しました" }));
    expect(await screen.findByText(/専用Chromeが閉じられました/)).toBeDefined();
  });

  test("保存処理だけが失敗した場合は同じChromeセッションで再試行する", async () => {
    let captures = 0;
    globalThis.fetch = (async (input, init) => {
      if (String(input).endsWith("/capture") && init?.method === "POST") {
        captures += 1;
        return Response.json({
          sessionId: "session-3",
          phase: "FAILED",
          account: null,
          errorCode: "LOGIN_FAILED",
        });
      }
      return Response.json({
        sessionId: "session-3",
        phase: "WAITING_USER",
        account: null,
        errorCode: null,
      });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(
      <LoginDialog
        translation={translate("ja")}
        onComplete={() => undefined}
        onClose={() => undefined}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "Xへのログインが完了しました" }));
    await user.click(await screen.findByRole("button", { name: "続ける" }));
    expect(captures).toBe(2);
  });
});
