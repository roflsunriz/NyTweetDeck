import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { AccountVaultSetup } from "./account-vault-setup";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("account vault setup", () => {
  test("validates confirmation, creates vault, and clears passphrase fields", async () => {
    const requests: Array<{ url: string; method: string; body: string | null }> = [];
    globalThis.fetch = (async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      requests.push({ url, method, body: typeof init?.body === "string" ? init.body : null });
      if (url.endsWith("/status")) {
        return Response.json({ exists: false, unlocked: false, accountCount: 0, unlockedAt: null });
      }
      if (url.endsWith("/accounts")) {
        return Response.json([]);
      }
      return new Response(null, { status: 204 });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<AccountVaultSetup translation={translate("ja")} />);

    const passphraseField = await screen.findByLabelText("Vaultパスフレーズ");
    const confirmationField = screen.getByLabelText("パスフレーズを確認");
    await user.type(passphraseField, "correct horse battery staple");
    await user.type(confirmationField, "different passphrase");
    await user.click(screen.getByRole("button", { name: "Vaultを作成" }));
    expect(screen.getByText("確認用パスフレーズが一致しません。")).toBeDefined();
    expect(requests.filter((request) => request.method === "POST")).toHaveLength(0);

    await user.clear(confirmationField);
    await user.type(confirmationField, "correct horse battery staple");
    await user.click(screen.getByRole("button", { name: "Vaultを作成" }));

    await waitFor(() => expect(screen.getByText("保存済みアカウントはありません。")).toBeDefined());
    expect(screen.queryByDisplayValue("correct horse battery staple")).toBeNull();
    const createRequest = requests.find((request) => request.url.endsWith("/create"));
    expect(createRequest?.body).toContain('"passphrase":"correct horse battery staple"');
  });
});
