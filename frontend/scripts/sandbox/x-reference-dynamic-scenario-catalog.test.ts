import { describe, expect, test } from "bun:test";

import { READ_ONLY_DYNAMIC_SCENARIOS } from "./x-reference-dynamic-scenario-catalog";

describe("X読み取り動的scenario台帳", () => {
  test("scenario IDが一意で、構造selectorだけを使用する", () => {
    const ids = READ_ONLY_DYNAMIC_SCENARIOS.map(({ id }) => id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.every((id) => /^[a-z][a-z0-9-]+$/.test(id))).toBeTrue();
    expect(
      READ_ONLY_DYNAMIC_SCENARIOS.every(({ action }) =>
        [action.selector, action.fallbackSelector]
          .filter((value): value is string => value !== undefined)
          .every((selector) => /^(?:a|article|button|\[)/.test(selector)),
      ),
    ).toBeTrue();
  });

  test("mutationを直接実行する要素を含めない", () => {
    const prohibited = /data-testid=\\?"(?:like|bookmark|reply)\\?"/i;
    expect(
      READ_ONLY_DYNAMIC_SCENARIOS.some(({ action }) =>
        [action.selector, action.fallbackSelector].some(
          (selector) => selector !== undefined && prohibited.test(selector),
        ),
      ),
    ).toBeFalse();
  });
});
