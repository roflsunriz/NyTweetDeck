import { afterEach, expect, test } from "bun:test";
import { act, cleanup, render, screen } from "@testing-library/react";
import { usePostTranslation } from "./use-post-translation";
import { useCommunityNoteTranslation } from "./use-community-note-translation";

const originalFetch = globalThis.fetch;
afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

function Post({
  accountId = "memory-post",
  text = "Original",
}: {
  accountId?: string;
  text?: string;
}) {
  const view = usePostTranslation({ accountId, postId: "123", text, language: "en", active: true });
  return <div data-loading={view.loading}>{view.visibleText}</div>;
}
function Note() {
  const view = useCommunityNoteTranslation(
    { noteId: "123", title: "Note", text: "Original", language: "en", footer: null },
    "memory-note",
    true,
  );
  return <div data-loading={view.loading}>{view.visibleNote.text}</div>;
}

test("post and note reappear synchronously from memory without new fetch or loading", async () => {
  let calls = 0;
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    calls++;
    return Response.json(
      String(input).includes("community-notes")
        ? { noteId: "123", targetLanguage: "ja", available: true, text: "ノート訳", sources: [] }
        : {
            postId: "123",
            sourceLanguage: "en",
            targetLanguage: "ja",
            text: "ポスト訳",
            provider: "X",
          },
    );
  }) as unknown as typeof fetch;
  const first = render(
    <>
      <Post />
      <Note />
    </>,
  );
  await screen.findByText("ポスト訳");
  await screen.findByText("ノート訳");
  expect(calls).toBe(2);
  first.unmount();
  render(
    <>
      <Post />
      <Note />
    </>,
  );
  expect(screen.getByText("ポスト訳").getAttribute("data-loading")).toBe("false");
  expect(screen.getByText("ノート訳").getAttribute("data-loading")).toBe("false");
  await act(async () => {
    await Promise.resolve();
  });
  expect(calls).toBe(2);
});

test("changing account or text cannot display a previous post translation", async () => {
  let calls = 0;
  globalThis.fetch = (async () => {
    calls++;
    if (calls > 1) return new Response(null, { status: 400 });
    return Response.json({
      postId: "123",
      sourceLanguage: "en",
      targetLanguage: "ja",
      text: "最初の訳",
      provider: "X",
    });
  }) as unknown as typeof fetch;
  const view = render(<Post accountId="memory-changing" />);
  await screen.findByText("最初の訳");
  view.rerender(<Post accountId="memory-changing" text="Edited" />);
  expect(screen.queryByText("最初の訳")).toBeNull();
  expect(screen.getByText("Edited")).toBeTruthy();
  await act(async () => {
    await Promise.resolve();
  });
});
