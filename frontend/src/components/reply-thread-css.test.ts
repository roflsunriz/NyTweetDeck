import { expect, test } from "bun:test";

test("uses logical RTL-safe thread connectors and narrows indentation on small viewports", async () => {
  const css = await Bun.file(new URL("../supplemental.css", import.meta.url)).text();
  const start = css.indexOf("/* reply-thread-start */");
  const end = css.indexOf("/* reply-thread-end */");
  expect(start).toBeGreaterThanOrEqual(0);
  expect(end).toBeGreaterThan(start);
  const threadCss = css.slice(start, end);

  expect(threadCss).toContain("padding-inline-start");
  expect(threadCss).toContain("inset-inline-start");
  expect(threadCss).toContain("border-inline-start");
  expect(threadCss).toContain("border-block-start");
  expect(threadCss).not.toMatch(/(?:^|[;{]\s*)(?:left|right|margin-left|padding-left)\s*:/mu);
  expect(css).toMatch(
    /@media \(max-width: 620px\)[\s\S]*?\.reply-thread-item\s*\{[\s\S]*?--reply-thread-step:\s*14px;/u,
  );
});
