export type ReadOnlyDynamicAction =
  | {
      fallbackSelector?: string;
      kind: "click" | "click-then-escape" | "focus";
      selector: string;
    }
  | { fallbackSelector?: string; index: number; kind: "click-index"; selector: string };

export interface ReadOnlyDynamicScenario {
  action: ReadOnlyDynamicAction;
  id: string;
}

export const READ_ONLY_DYNAMIC_SCENARIOS: readonly ReadOnlyDynamicScenario[] = [
  {
    action: { index: 1, kind: "click-index", selector: '[role="tab"]' },
    id: "home-following-tab",
  },
  {
    action: { kind: "click", selector: '[data-testid="AppTabBar_More_Menu"]' },
    id: "more-menu",
  },
  {
    action: {
      fallbackSelector: "button[data-testid]",
      kind: "click",
      selector: '[data-testid="SideNav_AccountSwitcher_Button"]',
    },
    id: "account-switcher",
  },
  {
    action: {
      fallbackSelector: 'a[href="/compose/post"]',
      kind: "click",
      selector: '[data-testid="SideNav_NewTweet_Button"]',
    },
    id: "composer-dialog",
  },
  {
    action: { kind: "click", selector: 'article[data-testid="tweet"] [data-testid="caret"]' },
    id: "post-overflow-menu",
  },
  {
    action: { kind: "click", selector: 'article[data-testid="tweet"] [data-testid="retweet"]' },
    id: "repost-menu",
  },
  {
    action: {
      kind: "click-then-escape",
      selector: 'article[data-testid="tweet"] [data-testid="caret"]',
    },
    id: "post-overflow-menu-escape",
  },
  {
    action: {
      kind: "click-then-escape",
      selector: 'article[data-testid="tweet"] [data-testid="retweet"]',
    },
    id: "repost-menu-escape",
  },
  {
    action: { kind: "click", selector: 'article[data-testid="tweet"] [data-testid="tweetText"]' },
    id: "post-detail",
  },
  {
    action: { kind: "click", selector: 'article[data-testid="tweet"] [data-testid="User-Name"] a' },
    id: "author-profile",
  },
  {
    action: {
      kind: "click",
      selector: 'article[data-testid="tweet"] [data-testid="tweetPhoto"] img',
    },
    id: "image-viewer",
  },
] as const;
