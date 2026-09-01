import { describe, expect, test } from "bun:test";

import { X_OBSERVED_INTERACTION_CONTRACTS } from "./x-observed-interaction-contracts";

describe("X観測済み意味契約", () => {
  test("契約IDが一意で、観測viewportと実装状態を明示する", () => {
    const ids = X_OBSERVED_INTERACTION_CONTRACTS.map(({ id }) => id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.every((id) => /^[a-z][a-z0-9-]+$/.test(id))).toBeTrue();
    expect(
      X_OBSERVED_INTERACTION_CONTRACTS.every(
        ({ rule, viewports }) => rule.length >= 15 && new Set(viewports).size === viewports.length,
      ),
    ).toBeTrue();
  });

  test("観測済み契約をWebとAndroidの両方へ取り込む", () => {
    const expected = [
      "post-overflow-nonmodal-menu",
      "repost-nonmodal-menu",
      "post-detail-route",
      "author-profile-route",
      "overlay-history-back",
      "image-viewer-responsive-presentation",
      "composer-responsive-presentation",
      "account-switcher-responsive-presentation",
      "url-entity-nullish-completion",
      "normalized-http-linkification",
      "inline-video-visibility-lifecycle",
      "inline-video-custom-controls",
    ];
    expect(
      X_OBSERVED_INTERACTION_CONTRACTS.filter(
        ({ desktopStatus }) => desktopStatus === "implemented",
      ).map(({ id }) => id),
    ).toEqual(expected);
    expect(
      X_OBSERVED_INTERACTION_CONTRACTS.filter(
        ({ androidStatus }) => androidStatus === "implemented",
      ).map(({ id }) => id),
    ).toEqual(expected);
  });
});
