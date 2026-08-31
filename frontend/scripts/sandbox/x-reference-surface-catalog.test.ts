import { describe, expect, test } from "bun:test";

import { READ_ONLY_X_SURFACES } from "./x-reference-surface-catalog";

describe("X読み取りsurface巡回台帳", () => {
  test("IDとpathが一意で、静的なX相対pathだけを使用する", () => {
    const ids = READ_ONLY_X_SURFACES.map(({ id }) => id);
    const paths = READ_ONLY_X_SURFACES.map(({ path }) => path);
    expect(new Set(ids).size).toBe(ids.length);
    expect(new Set(paths).size).toBe(paths.length);
    expect(ids.every((id) => /^[a-z][a-z0-9-]+$/.test(id))).toBeTrue();
    expect(
      paths.every((path) => /^\/[a-zA-Z0-9_/-]*$/.test(path) && !path.includes("..")),
    ).toBeTrue();
  });

  test("変更操作を起動するmutation URLを含めない", () => {
    expect(
      READ_ONLY_X_SURFACES.some(({ path }) =>
        /\/(?:create|delete|destroy|like|retweet|follow|unfollow)(?:\/|$)/i.test(path),
      ),
    ).toBeFalse();
  });
});
