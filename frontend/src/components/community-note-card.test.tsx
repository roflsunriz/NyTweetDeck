import { afterEach, expect, test } from "bun:test";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { translate } from "../i18n/translations";
import { CommunityNoteCard } from "./community-note-card";
import { PostTranslationProvider } from "./post-translation-context";

const originalFetch = globalThis.fetch;
afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});
const note = {
  noteId: "555",
  title: "Context",
  text: "Original source",
  language: "en",
  isTranslatable: true,
  footer: null,
  sources: [{ fromIndex: 9, toIndex: 15, url: "https://example.com/original" }],
};

test("uses the note endpoint and translation links then restores original sources", async () => {
  const urls: string[] = [];
  globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
    urls.push(String(input));
    return Response.json({
      noteId: "555",
      targetLanguage: "ja",
      available: true,
      text: "説明 出典",
      sources: [{ fromIndex: 3, toIndex: 5, url: "https://example.com/translated" }],
    });
  }) as unknown as typeof fetch;
  render(
    <CommunityNoteCard note={note} accountId="note-test" active translation={translate("ja")} />,
  );
  await waitFor(() =>
    expect(screen.getByRole("link").getAttribute("href")).toBe("https://example.com/translated"),
  );
  expect(urls[0]).toContain("/community-notes/555/translation?");
  fireEvent.click(screen.getByRole("button", { name: translate("ja").showOriginal }));
  expect(screen.getByRole("link").getAttribute("href")).toBe("https://example.com/original");
});

test("does not translate when disabled, same-language, or without prose", () => {
  let calls = 0;
  globalThis.fetch = (async () => {
    calls++;
    throw new Error("unexpected");
  }) as unknown as typeof fetch;
  render(
    <PostTranslationProvider
      value={{ locale: "ja", autoTranslatePosts: false, setAutoTranslatePosts: () => {} }}
    >
      <CommunityNoteCard note={note} accountId="disabled" active translation={translate("ja")} />
    </PostTranslationProvider>,
  );
  render(
    <CommunityNoteCard
      note={{ ...note, language: "ja" }}
      accountId="same"
      active
      translation={translate("ja")}
    />,
  );
  render(
    <CommunityNoteCard
      note={{ ...note, text: "@user https://t.co/image" }}
      accountId="empty"
      active
      translation={translate("ja")}
    />,
  );
  expect(calls).toBe(0);
});

test("keeps unprovided translation separate from failure and retries manually", async () => {
  let calls = 0;
  globalThis.fetch = (async () => {
    calls++;
    return Response.json({
      noteId: "555",
      targetLanguage: "ja",
      available: calls > 1,
      text: calls > 1 ? "翻訳済み" : null,
      sources: [],
    });
  }) as unknown as typeof fetch;
  render(
    <CommunityNoteCard note={note} accountId="unavailable" active translation={translate("ja")} />,
  );
  await screen.findByText(translate("ja").translationUnavailable);
  expect(calls).toBe(1);
  fireEvent.click(screen.getByRole("button", { name: translate("ja").retry }));
  await screen.findByText("翻訳済み");
  expect(calls).toBe(2);
});

test("uses known source language despite a translatability flag from another display language and deduplicates cards", async () => {
  let calls = 0;
  globalThis.fetch = (async () => {
    calls++;
    return Response.json({
      noteId: "555",
      targetLanguage: "ja",
      available: true,
      text: "共有された翻訳",
      sources: [],
    });
  }) as unknown as typeof fetch;
  const content = (
    <CommunityNoteCard
      note={{ ...note, isTranslatable: false }}
      accountId="shared"
      active
      translation={translate("ja")}
    />
  );
  render(
    <>
      {content}
      {content}
    </>,
  );
  await waitFor(() => expect(screen.getAllByText("共有された翻訳").length).toBe(2));
  expect(calls).toBe(1);
});

test("does not carry a previous translation to another note", async () => {
  globalThis.fetch = (async () =>
    Response.json({
      noteId: "555",
      targetLanguage: "ja",
      available: true,
      text: "最初の翻訳",
      sources: [],
    })) as unknown as typeof fetch;
  const view = render(
    <CommunityNoteCard note={note} accountId="changing" active translation={translate("ja")} />,
  );
  await screen.findByText("最初の翻訳");
  view.rerender(
    <CommunityNoteCard
      note={{ ...note, noteId: "556", text: "新しい日本語", language: "ja" }}
      accountId="changing"
      active
      translation={translate("ja")}
    />,
  );
  expect(screen.queryByText("最初の翻訳")).toBeNull();
  expect(screen.getByText("新しい日本語")).toBeTruthy();
});
