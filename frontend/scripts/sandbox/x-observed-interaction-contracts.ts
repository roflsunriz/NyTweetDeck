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
    desktopStatus: "implemented",
    id: "post-detail-route",
    rule: "modalを増やさずポスト固有routeへ遷移する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "unassessed",
    desktopStatus: "implemented",
    id: "author-profile-route",
    rule: "modalを増やさず作者固有routeへ遷移する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "unassessed",
    desktopStatus: "implemented",
    id: "overlay-history-back",
    rule: "Composer、プロフィール、ポスト詳細、mediaを履歴へ積み、Backで一段ずつ背後のカラム状態へ復帰する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "known-gap",
    desktopStatus: "implemented",
    id: "image-viewer-responsive-presentation",
    rule: "desktopとtabletは詳細route上のmodal、phoneはmodalを持たない全画面routeとして表示する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "known-gap",
    desktopStatus: "implemented",
    id: "composer-responsive-presentation",
    rule: "desktopとtabletはcompose route上のmodal、phoneはmodalを持たない全画面routeとして表示する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "unassessed",
    desktopStatus: "implemented",
    id: "account-switcher-responsive-presentation",
    rule: "phoneはhome routeを維持した非modal dialogとexpanded triggerで開き、広幅は別の非modal構造を使う",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "implemented",
    desktopStatus: "implemented",
    id: "url-entity-nullish-completion",
    rule: "display URLとexpanded URLのnullish欠落を短縮URLで補完し、空文字と既存値は維持する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "implemented",
    desktopStatus: "implemented",
    id: "normalized-http-linkification",
    rule: "正規化済みHTTPとHTTPSを末尾句読点・余分な閉じ括弧から分離して操作可能なリンクとして表示する",
    viewports: ["desktop", "tablet", "phone"],
  },
  {
    androidStatus: "unassessed",
    desktopStatus: "implemented",
    id: "inline-video-visibility-lifecycle",
    rule: "表示中はミュート再生し、画面外でvideo要素を切断・初期化し、再入場時に新要素でミュート再生を再開する",
    viewports: ["desktop"],
  },
  {
    androidStatus: "unassessed",
    desktopStatus: "implemented",
    id: "inline-video-custom-controls",
    rule: "native controlsを表示せず、再生・音量・seek・fullscreen等を独自UIとして提供する",
    viewports: ["desktop"],
  },
] as const;
