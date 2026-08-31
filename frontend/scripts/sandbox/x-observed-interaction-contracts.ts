export type ParityStatus = "implemented" | "known-gap" | "unassessed";

export interface ObservedInteractionContract {
  androidStatus: ParityStatus;
  desktopStatus: ParityStatus;
  id: string;
  rule: string;
  viewports: readonly ("desktop" | "tablet" | "phone")[];
}

export const X_OBSERVED_INTERACTION_CONTRACTS: readonly ObservedInteractionContract[] = [
  {
    androidStatus: "known-gap",
    desktopStatus: "implemented",
    id: "post-overflow-nonmodal-menu",
    rule: "routeを維持してmenuとtrigger expandedを1つ増やし、Escapeで両方を0へ戻す",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "unassessed",
    desktopStatus: "implemented",
    id: "repost-nonmodal-menu",
    rule: "routeを維持してmenuとtrigger expandedを1つ増やし、Escapeで両方を0へ戻す",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "unassessed",
    desktopStatus: "known-gap",
    id: "post-detail-route",
    rule: "modalを増やさずポスト固有routeへ遷移する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "unassessed",
    desktopStatus: "known-gap",
    id: "author-profile-route",
    rule: "modalを増やさず作者固有routeへ遷移する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "known-gap",
    desktopStatus: "known-gap",
    id: "image-viewer-responsive-presentation",
    rule: "desktopとtabletは詳細route上のmodal、phoneはmodalを持たない全画面routeとして表示する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "known-gap",
    desktopStatus: "known-gap",
    id: "composer-responsive-presentation",
    rule: "desktopとtabletはcompose route上のmodal、phoneはmodalを持たない全画面routeとして表示する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "unassessed",
    desktopStatus: "unassessed",
    id: "account-switcher-responsive-presentation",
    rule: "phoneはhome routeを維持した非modal dialogとexpanded triggerで開き、広幅は別の非modal構造を使う",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "unassessed",
    desktopStatus: "unassessed",
    id: "url-entity-nullish-completion",
    rule: "display URLとexpanded URLのnullish欠落を短縮URLで補完し、空文字と既存値は維持する",
    viewports: ["desktop", "tablet", "phone"],
  },
] as const;
