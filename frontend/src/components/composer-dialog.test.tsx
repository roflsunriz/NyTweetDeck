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

    expect(screen.getByText("保存済みアカウントはありません。")).toBeDefined();
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

  test("uses a non-modal full-page region at phone width", () => {
    const originalMatchMedia = window.matchMedia;
    try {
      window.matchMedia = matchMediaResult(true);
      render(
        <ComposerDialog
          translation={translate("ja")}
          accountId="account-1"
          onClose={() => undefined}
        />,
      );
      expect(screen.queryByRole("dialog", { name: "ポストを作成" })).toBeNull();
      const region = screen.getByRole("region", { name: "ポストを作成" });
      expect(region.getAttribute("aria-modal")).toBeNull();
      expect(region.getAttribute("data-presentation")).toBe("full-page");
    } finally {
      cleanup();
      window.matchMedia = originalMatchMedia;
    }
  });

  test("uploads an image before publishing a media-only post", async () => {
    const requests: string[] = [];
    let postBody: Record<string, unknown> | null = null;
    globalThis.fetch = (async (input, init) => {
      requests.push(String(input));
      if (String(input).endsWith("/media")) {
        if (!(init?.body instanceof FormData)) throw new Error("画像送信フォームがありません。");
        expect(init.body.get("accountId")).toBe("account-1");
        return Response.json({ mediaId: "456", mimeType: "image/png" });
      }
      postBody = JSON.parse(String(init?.body)) as Record<string, unknown>;
      return Response.json({ id: "posted" });
    }) as typeof fetch;
    const onPublished = mock(() => undefined);
    const user = userEvent.setup();
    render(
      <ComposerDialog
        translation={translate("ja")}
        accountId="account-1"
        onClose={() => undefined}
        onPublished={onPublished}
      />,
    );
    const picker = document.querySelector('input[accept="image/jpeg,image/png"]');
    if (!(picker instanceof HTMLInputElement)) throw new Error("画像選択が見つかりません。");
    await user.upload(
      picker,
      new File([new Uint8Array([137, 80, 78, 71])], "photo.png", { type: "image/png" }),
    );
    await user.click(screen.getByRole("button", { name: "ポストする" }));

    expect(requests).toEqual(["/api/v1/posts/media", "/api/v1/posts"]);
    expect(postBody).toMatchObject({ text: "", mediaIds: ["456"] });
    expect(onPublished).toHaveBeenCalledTimes(1);
  });

  test("keeps the draft and does not publish when an image upload fails", async () => {
    const requests: string[] = [];
    globalThis.fetch = (async (input) => {
      requests.push(String(input));
      return Response.json({ detail: "画像が大きすぎます。" }, { status: 413 });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(
      <ComposerDialog
        translation={translate("ja")}
        accountId="account-1"
        onClose={() => undefined}
      />,
    );
    const picker = document.querySelector('input[accept="image/jpeg,image/png"]');
    if (!(picker instanceof HTMLInputElement)) throw new Error("画像選択が見つかりません。");
    await user.upload(
      picker,
      new File([new Uint8Array([137, 80, 78, 71])], "photo.png", { type: "image/png" }),
    );
    await user.click(screen.getByRole("button", { name: "ポストする" }));

    expect((await screen.findByRole("alert")).textContent).toContain("画像が大きすぎます。");
    expect(screen.getByText("photo.png")).toBeDefined();
    expect(requests).toEqual(["/api/v1/posts/media"]);
  });

  test("inserts emoji and publishes a poll with both disclosure flags", async () => {
    let postBody: Record<string, unknown> | null = null;
    globalThis.fetch = (async (_input, init) => {
      postBody = JSON.parse(String(init?.body)) as Record<string, unknown>;
      return Response.json({ id: "posted" });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(
      <ComposerDialog
        translation={translate("ja")}
        accountId="account-1"
        onClose={() => undefined}
      />,
    );

    await user.type(screen.getByPlaceholderText("いまどうしてる？"), "質問");
    await user.click(screen.getByRole("button", { name: "絵文字" }));
    await user.click(screen.getByRole("button", { name: "🎉" }));
    await user.click(screen.getByRole("button", { name: "投票" }));
    await user.type(screen.getByRole("textbox", { name: "選択肢1" }), "はい");
    await user.type(screen.getByRole("textbox", { name: "選択肢2" }), "いいえ");
    await user.click(screen.getByRole("button", { name: "コンテンツ開示" }));
    await user.click(screen.getByRole("checkbox", { name: "有料パートナーシップ" }));
    await user.click(screen.getByRole("checkbox", { name: "AI生成メディア" }));
    await user.click(screen.getByRole("button", { name: "ポストする" }));

    expect(postBody).toMatchObject({
      text: "質問🎉",
      poll: { choices: ["はい", "いいえ"], durationMinutes: 1440 },
      paidPartnership: true,
      aiGenerated: true,
    });
  });

  test("uses the selected X place and schedules separately without publishing immediately", async () => {
    const payloads: Array<Record<string, unknown>> = [];
    globalThis.fetch = (async (input, init) => {
      if (String(input).includes("/places?")) {
        return Response.json([{ id: "abc123", name: "Tokyo, Japan", country: "Japan" }]);
      }
      payloads.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
      return Response.json({ scheduledId: "999" });
    }) as typeof fetch;
    const user = userEvent.setup();
    const first = render(
      <ComposerDialog
        translation={translate("ja")}
        accountId="account-1"
        onClose={() => undefined}
      />,
    );
    await user.type(screen.getByPlaceholderText("いまどうしてる？"), "東京から");
    await user.click(screen.getByRole("button", { name: "場所" }));
    await user.type(screen.getByPlaceholderText("都市または場所"), "Tokyo");
    await user.click(screen.getByRole("button", { name: "検索" }));
    await user.click(await screen.findByRole("button", { name: "Tokyo, Japan" }));
    await user.click(screen.getByRole("button", { name: "ポストする" }));
    expect(payloads[0]).toMatchObject({ place: { id: "abc123" } });
    first.unmount();

    const onPublished = mock(() => undefined);
    render(
      <ComposerDialog
        translation={translate("ja")}
        accountId="account-1"
        onClose={() => undefined}
        onPublished={onPublished}
      />,
    );
    await user.type(screen.getByPlaceholderText("いまどうしてる？"), "あとで投稿");
    await user.click(screen.getByRole("button", { name: "予約" }));
    await user.click(screen.getByRole("button", { name: "予約する" }));
    expect(typeof payloads[1]?.scheduledAt).toBe("string");
    expect(screen.getByRole("status").textContent).toContain("予約投稿を保存しました。");
    expect(onPublished).not.toHaveBeenCalled();
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
