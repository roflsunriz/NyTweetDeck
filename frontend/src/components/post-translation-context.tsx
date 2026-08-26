import { createContext, type ReactNode, useContext } from "react";
import type { Locale } from "../model/layout";

interface PostTranslationSettings {
  locale: Locale;
  autoTranslatePosts: boolean;
  setAutoTranslatePosts: (enabled: boolean) => void;
}

const defaultSettings: PostTranslationSettings = {
  locale: "ja",
  autoTranslatePosts: true,
  setAutoTranslatePosts: () => undefined,
};

const PostTranslationContext = createContext<PostTranslationSettings>(defaultSettings);

export function PostTranslationProvider({
  value,
  children,
}: {
  value: PostTranslationSettings;
  children: ReactNode;
}) {
  return (
    <PostTranslationContext.Provider value={value}>{children}</PostTranslationContext.Provider>
  );
}

export function usePostTranslationSettings(): PostTranslationSettings {
  return useContext(PostTranslationContext);
}
