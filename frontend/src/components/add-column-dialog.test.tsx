import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { AddColumnDialog } from "./add-column-dialog";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("column target selection", () => {
  test("resolves an X username before adding a user column", async () => {
    globalThis.fetch = (async () =>
      Response.json({
        id: "42",
        username: "alice",
        displayName: "Alice",
        avatarUrl: null,
      })) as unknown as typeof fetch;
    const added: unknown[] = [];
    const user = userEvent.setup();
    render(
      <AddColumnDialog
        translation={translate("ja")}
        accountId="account-1"
        initialKind="user"
        onAdd={(...values) => added.push(values)}
        onClose={() => undefined}
      />,
    );

    await user.type(screen.getByPlaceholderText(/ユーザー名/), "@alice");
    await user.click(screen.getByRole("button", { name: "このカラムを追加" }));

    expect(added).toEqual([["user", "42", "@alice"]]);
  });

  test("shows account lists and adds the selected list by name", async () => {
    globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) =>
      Response.json({
        lists: String(input).includes("scope=mine")
          ? [
              {
                id: "84",
                name: "Friends",
                description: "People I know",
                ownerName: "Alice",
                ownerUsername: "alice",
                memberCount: 5,
                subscriberCount: 2,
                source: "mine",
              },
            ]
          : [],
        nextCursor: null,
      })) as unknown as typeof fetch;
    const added: unknown[] = [];
    const user = userEvent.setup();
    render(
      <AddColumnDialog
        translation={translate("ja")}
        accountId="account-1"
        initialKind="list"
        onAdd={(...values) => added.push(values)}
        onClose={() => undefined}
      />,
    );

    await user.click(await screen.findByRole("button", { name: /Friends/ }));

    expect(added).toEqual([["list", "84", "Friends"]]);
  });
});
