import { describe, expect, test } from "bun:test";

import {
  coverageCatalogCounts,
  NYTD_PRODUCT_INVARIANTS,
  X_REFERENCE_COVERAGE,
} from "./x-reference-coverage-catalog";

describe("X Web全要素カバレッジ台帳", () => {
  test("各軸のIDが空でなく軸内で一意である", () => {
    for (const [dimension, values] of Object.entries(X_REFERENCE_COVERAGE)) {
      expect(values.length, dimension).toBeGreaterThan(0);
      expect(new Set(values).size, dimension).toBe(values.length);
      expect(
        values.every((value) => /^[a-z][a-z0-9-]+$/.test(value)),
        dimension,
      ).toBeTrue();
    }
  });

  test("特定5領域だけでなく全surface・状態・入力・環境を保持する", () => {
    expect(X_REFERENCE_COVERAGE.elementKinds).toContain("video-player");
    expect(X_REFERENCE_COVERAGE.elementKinds).toContain("image-viewer");
    expect(X_REFERENCE_COVERAGE.elementKinds).toContain("reply-tree");
    expect(X_REFERENCE_COVERAGE.elementKinds).toContain("post-text-entity");
    expect(X_REFERENCE_COVERAGE.elementKinds).toContain("author-identity");
    expect(X_REFERENCE_COVERAGE.elementKinds).toContain("virtualized-list");
    expect(X_REFERENCE_COVERAGE.surfaces).toContain("dialogs-popovers-menus-toasts");
    expect(X_REFERENCE_COVERAGE.inputs).toContain("touch-pinch");
    expect(X_REFERENCE_COVERAGE.environments).toContain("keyboard-only");
  });

  test("台帳件数を機械的に集計できる", () => {
    expect(coverageCatalogCounts()).toEqual({
      contentKinds: 23,
      dataConditions: 23,
      elementKinds: 30,
      environments: 15,
      inputs: 19,
      states: 31,
      surfaces: 31,
    });
  });

  test("X互換化で壊してはいけないNyTD固有の製品不変条件を固定する", () => {
    expect(NYTD_PRODUCT_INVARIANTS.map(({ id }) => id)).toEqual([
      "freely-addable-unbounded-columns",
      "persistent-left-main-menu",
      "customizable-main-menu",
      "persistent-multiple-accounts",
    ]);
    expect(NYTD_PRODUCT_INVARIANTS.every(({ rule }) => rule.length > 20)).toBeTrue();
  });
});
