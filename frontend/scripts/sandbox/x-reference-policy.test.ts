import { describe, expect, test } from "bun:test";

import { makeCapturedAssetFileName, sanitizeOfficialAssetUrl, sha256 } from "./x-reference-policy";

describe("X公式資産sandboxポリシー", () => {
  test("許可した公式CDNのJavaScriptとCSSだけを受け入れ、queryとfragmentを除去する", () => {
    expect(
      sanitizeOfficialAssetUrl(
        "https://abs.twimg.com/responsive-web/client-web/bundle.Home.012345.js?token=secret#part",
      ),
    ).toBe("https://abs.twimg.com/responsive-web/client-web/bundle.Home.012345.js");
    expect(
      sanitizeOfficialAssetUrl("https://abs.twimg.com/x-web/x-web/assets/main.012345.css"),
    ).toBe("https://abs.twimg.com/x-web/x-web/assets/main.012345.css");
  });

  test("HTML、画像、非公式host、userinfo付きhost偽装を拒否する", () => {
    expect(sanitizeOfficialAssetUrl("https://x.com/home")).toBeNull();
    expect(
      sanitizeOfficialAssetUrl("https://abs.twimg.com/responsive-web/client-web/icon.png"),
    ).toBeNull();
    expect(
      sanitizeOfficialAssetUrl(
        "https://abs.twimg.com.evil.example/responsive-web/client-web/bundle.js",
      ),
    ).toBeNull();
    expect(
      sanitizeOfficialAssetUrl(
        "https://abs.twimg.com@evil.example/responsive-web/client-web/bundle.js",
      ),
    ).toBeNull();
  });

  test("保存名とハッシュを内容から決定する", () => {
    expect(
      makeCapturedAssetFileName(
        "https://abs.twimg.com/responsive-web/client-web/bundle.Home.012345.js",
      ),
    ).toMatch(/^[0-9a-f]{12}-bundle\.Home\.012345\.js$/);
    expect(sha256(new TextEncoder().encode("reference"))).toBe(
      "52367a6622b19f08825e915fad80c542ad4f4c34dbcebad9f5007994b3e39208",
    );
  });
});
