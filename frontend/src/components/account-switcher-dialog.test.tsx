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

    await user.click(await screen.findByRole("button", { name: "Xアカウントにログイン" }));

    expect(onLogin).toHaveBeenCalledTimes(1);
  });
});
