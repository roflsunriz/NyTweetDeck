export interface ReadOnlySurfaceDefinition {
  id: string;
  path: string;
}

export const READ_ONLY_X_SURFACES: readonly ReadOnlySurfaceDefinition[] = [
  { id: "home-for-you", path: "/home" },
  { id: "search-results", path: "/search" },
  { id: "explore-trends", path: "/explore" },
  { id: "notifications", path: "/notifications" },
  { id: "history-bookmarks", path: "/i/bookmarks" },
  { id: "lists-directory", path: "/i/lists" },
  { id: "direct-messages-inbox", path: "/messages" },
  { id: "communities", path: "/communities" },
  { id: "grok-chat", path: "/i/grok" },
  { id: "settings", path: "/settings" },
  { id: "premium-creator-business", path: "/i/premium_sign_up" },
  { id: "composer-post", path: "/compose/post" },
] as const;
