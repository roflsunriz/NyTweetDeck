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
  test("routes to vault settings instead of offering a login that must fail while locked", async () => {
    globalThis.fetch = (async () =>
      Response.json({
        exists: false,
        unlocked: false,
        accountCount: 0,
      })) as unknown as typeof fetch;
    const onSetup = mock(() => undefined);
    const user = userEvent.setup();
    render(
      <AccountSwitcherDialog
        translation={translate("ja")}
        activeAccountId={null}
        onSelect={() => undefined}
        onLogin={() => undefined}
        onSetup={onSetup}
        onClose={() => undefined}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "Vault設定を開く" }));

    expect(onSetup).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "Xアカウントにログイン" })).toBeNull();
  });
});
