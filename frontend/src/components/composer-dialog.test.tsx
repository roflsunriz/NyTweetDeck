import { afterEach, describe, expect, mock, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { ComposerDialog } from "./composer-dialog";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("composer", () => {
  test("publishes a reply for the active account and clears the dialog", async () => {
    let requestBody = "";
    globalThis.fetch = (async (_input, init) => {
      requestBody = String(init?.body);
      return Response.json({ id: "new-post" });
    }) as typeof fetch;
    const onClose = mock(() => undefined);
    const onPublished = mock(() => undefined);
    const user = userEvent.setup();
    render(
      <ComposerDialog
        translation={translate("ja")}
        accountId="account-1"
        inReplyToPostId="100"
        onClose={onClose}
        onPublished={onPublished}
      />,
    );

    await user.type(screen.getByPlaceholderText("いまどうしてる？"), "返信本文");
    await user.click(screen.getByRole("button", { name: "ポストする" }));

    expect(requestBody).toContain('"accountId":"account-1"');
    expect(requestBody).toContain('"text":"返信本文"');
    expect(requestBody).toContain('"inReplyToPostId":"100"');
    expect(onPublished).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test("does not render credential input when no account is active", () => {
    render(
      <ComposerDialog translation={translate("ja")} accountId={null} onClose={() => undefined} />,
    );

    expect(screen.getByText("Vaultを解除し、アカウントへログインしてください。")).toBeDefined();
    expect(screen.queryByPlaceholderText("いまどうしてる？")).toBeNull();
  });

  test("publishes a quote using a post id instead of accepting an arbitrary attachment URL", async () => {
    let requestBody = "";
    globalThis.fetch = (async (_input, init) => {
      requestBody = String(init?.body);
      return Response.json({ id: "quote-post" });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(
      <ComposerDialog
        translation={translate("ja")}
        accountId="account-1"
        quotePostId="100"
        quotePostUrl="https://x.com/alice/status/100"
        onClose={() => undefined}
      />,
    );

    expect(screen.getByRole("heading", { name: "引用" })).toBeDefined();
    await user.type(screen.getByPlaceholderText("いまどうしてる？"), "引用本文");
    await user.click(screen.getByRole("button", { name: "ポストする" }));

    const payload = JSON.parse(requestBody) as Record<string, unknown>;
    expect(payload.quotePostId).toBe("100");
    expect(payload).not.toHaveProperty("attachmentUrl");
  });
});
