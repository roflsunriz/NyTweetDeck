import { afterEach, describe, expect, mock, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { AccountSwitcherDialog } from "./account-switcher-dialog";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("account switcher", () => {
  test("offers login immediately when no account has been saved", async () => {
    globalThis.fetch = (async () => Response.json([])) as unknown as typeof fetch;
    const onLogin = mock(() => undefined);
    const user = userEvent.setup();
    render(
      <AccountSwitcherDialog
        translation={translate("ja")}
        activeAccountId={null}
        onSelect={() => undefined}
        onLogin={onLogin}
        onClose={() => undefined}
      />,
    );

    await user.click(await screen.findByRole("menuitem", { name: "Xアカウントにログイン" }));

    expect(onLogin).toHaveBeenCalledTimes(1);
  });

  test("uses a wide non-modal group and a phone non-modal dialog", async () => {
    globalThis.fetch = (async () =>
      Response.json([
        {
          accountId: "account-1",
          userId: "1",
          username: "alice",
          displayName: "Alice",
        },
      ])) as unknown as typeof fetch;
    const wide = render(
      <AccountSwitcherDialog
        translation={translate("ja")}
        activeAccountId="account-1"
        onSelect={() => undefined}
        onLogin={() => undefined}
        onClose={() => undefined}
      />,
    );
    const group = await screen.findByRole("group", { name: "アカウントを選択" });
    expect(group.getAttribute("aria-modal")).toBeNull();
    expect(group.getAttribute("data-presentation")).toBe("wide-group");
    expect(screen.getByRole("menuitem", { name: /Alice/ })).toBeDefined();
    wide.unmount();

    const originalMatchMedia = window.matchMedia;
    try {
      window.matchMedia = matchMediaResult(true);
      render(
        <AccountSwitcherDialog
          translation={translate("ja")}
          activeAccountId="account-1"
          onSelect={() => undefined}
          onLogin={() => undefined}
          onClose={() => undefined}
        />,
      );
      const dialog = await screen.findByRole("dialog", { name: "アカウントを選択" });
      expect(dialog.getAttribute("aria-modal")).toBeNull();
      expect(dialog.getAttribute("data-presentation")).toBe("phone-dialog");
    } finally {
      window.matchMedia = originalMatchMedia;
    }
  });
});

function matchMediaResult(matches: boolean): typeof window.matchMedia {
  return ((query: string) => ({
    addEventListener: () => undefined,
    addListener: () => undefined,
    dispatchEvent: () => true,
    matches,
    media: query,
    onchange: null,
    removeEventListener: () => undefined,
    removeListener: () => undefined,
  })) as typeof window.matchMedia;
}
