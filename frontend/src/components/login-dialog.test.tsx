import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { LoginDialog } from "./login-dialog";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("Android OCF login", () => {
  test("submits dynamic steps and selects the completed account", async () => {
    let submittedBody = "";
    globalThis.fetch = (async (input, init) => {
      if (String(input).endsWith("/start")) {
        return Response.json({
          sessionId: "session-1",
          complete: false,
          subtasks: [
            {
              id: "LoginEnterUserIdentifier",
              type: "TEXT",
              prompt: "ユーザー名",
              hint: null,
              nextLink: "next_link",
              choices: [],
            },
          ],
          account: null,
        });
      }
      submittedBody = String(init?.body);
      return Response.json({
        sessionId: null,
        complete: true,
        subtasks: [],
        account: { accountId: "42" },
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
        onClose={() => {}}
      />,
    );

    await user.type(await screen.findByLabelText("ユーザー名"), "alice");
    await user.click(screen.getByRole("button", { name: "続ける" }));

    expect(JSON.parse(submittedBody).value).toBe("alice");
    expect(selected).toBe("42");
  });
});
