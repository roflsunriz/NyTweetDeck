import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { defaultNavItemIds, type NavItemId } from "../model/layout";
import { MenuEditorDialog } from "./menu-editor-dialog";

afterEach(cleanup);

describe("main menu editor", () => {
  test("adds and removes optional and default items", async () => {
    let selected: NavItemId[] = [...defaultNavItemIds];
    const user = userEvent.setup();
    const view = render(
      <MenuEditorDialog
        translation={translate("ja")}
        selected={selected}
        onChange={(items) => {
          selected = items;
        }}
        onClose={() => undefined}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Grok" }));
    expect(selected).toContain("grok");

    view.rerender(
      <MenuEditorDialog
        translation={translate("ja")}
        selected={selected}
        onChange={(items) => {
          selected = items;
        }}
        onClose={() => undefined}
      />,
    );
    await user.click(screen.getByRole("button", { name: "トレンド" }));
    expect(selected).not.toContain("trends");
  });
});
