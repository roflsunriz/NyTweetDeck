import { describe, expect, test } from "bun:test";

import { extractNamedFunction } from "./official-function-extractor";

describe("公式minified関数抽出", () => {
  test("文字列とtemplate内の波括弧を関数終端として扱わない", () => {
    const interpolation = "$" + "{e}:$" + "{JSON.stringify(t)}";
    const source = `prefix;function n(e){let t={nested:{value:"}"}};return \`${interpolation}\`}suffix`;
    expect(extractNamedFunction(source, "n")).toBe(
      `function n(e){let t={nested:{value:"}"}};return \`${interpolation}\`}`,
    );
  });

  test("指定位置より前にある同名関数を選ばない", () => {
    const source = "function n(){return 1} marker function n(){return 2}";
    expect(extractNamedFunction(source, "n", source.indexOf("marker"))).toBe(
      "function n(){return 2}",
    );
  });

  test("不正名と欠落関数を拒否する", () => {
    expect(() => extractNamedFunction("", "n-name")).toThrow();
    expect(() => extractNamedFunction("const n = 1", "n")).toThrow();
  });
});
