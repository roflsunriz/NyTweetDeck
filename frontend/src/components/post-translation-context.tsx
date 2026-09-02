import { createContext, type ReactNode, useContext } from "react";
import type { Locale, ReplySort } from "../model/layout";

interface PostTranslationSettings {
  locale: Locale;
  translationLocale: Locale;
  autoTranslatePosts: boolean;
  setAutoTranslatePosts: (enabled: boolean) => void;
  replySort: ReplySort;
  setReplySort: (replySort: ReplySort) => void;
}

const defaultSettings: PostTranslationSettings = {
  locale: "ja",
  translationLocale: "ja",
  autoTranslatePosts: true,
  setAutoTranslatePosts: () => undefined,
  replySort: "relevance",
  setReplySort: () => undefined,
};

const PostTranslationContext = createContext<PostTranslationSettings>(defaultSettings);

type PostTranslationProviderValue = Omit<
  PostTranslationSettings,
  "translationLocale" | "replySort" | "setReplySort"
> &
  Partial<Pick<PostTranslationSettings, "translationLocale" | "replySort" | "setReplySort">>;

export function PostTranslationProvider({
  value,
  children,
}: {
  value: PostTranslationProviderValue;
  children: ReactNode;
}) {
  return (
    <PostTranslationContext.Provider
      value={{
        ...defaultSettings,
        ...value,
        translationLocale: value.translationLocale ?? value.locale,
      }}
    >
      {children}
    </PostTranslationContext.Provider>
  );
}

export function usePostTranslationSettings(): PostTranslationSettings {
  return useContext(PostTranslationContext);
}
