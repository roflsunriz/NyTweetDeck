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

  test("未比較を実装済みとして扱わず、既知gapを追跡する", () => {
    expect(
      X_OBSERVED_INTERACTION_CONTRACTS.some(
        ({ androidStatus, desktopStatus }) =>
          androidStatus === "known-gap" || desktopStatus === "known-gap",
      ),
    ).toBeTrue();
    expect(
      X_OBSERVED_INTERACTION_CONTRACTS.filter(
        ({ desktopStatus }) => desktopStatus === "implemented",
      ).map(({ id }) => id),
    ).toEqual([
      "post-overflow-nonmodal-menu",
      "repost-nonmodal-menu",
      "normalized-http-linkification",
    ]);
  });
});
