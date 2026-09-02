import { expect, test } from "bun:test";
import { selectVideoSource } from "./video-quality";

const variants = [
  { url: "https://video.example/low.mp4", bitrate: 256_000 },
  { url: "https://video.example/mid.mp4", bitrate: 832_000 },
  { url: "https://video.example/high.mp4", bitrate: 2_000_000 },
];

test("selects the requested video quality without changing the source list", () => {
  // biome-ignore lint/style/noNonNullAssertion: variants is a fixed test fixture
  expect(selectVideoSource("fallback.mp4", variants, "low")).toBe(variants[0]!.url);
  // biome-ignore lint/style/noNonNullAssertion: variants is a fixed test fixture
  expect(selectVideoSource("fallback.mp4", variants, "medium")).toBe(variants[1]!.url);
  // biome-ignore lint/style/noNonNullAssertion: variants is a fixed test fixture
  expect(selectVideoSource("fallback.mp4", variants, "high")).toBe(variants[2]!.url);
  // biome-ignore lint/style/noNonNullAssertion: variants is a fixed test fixture
  expect(selectVideoSource("fallback.mp4", variants, "auto")).toBe(variants[2]!.url);
  expect(variants.map((variant) => variant.url)).toEqual([
    "https://video.example/low.mp4",
    "https://video.example/mid.mp4",
    "https://video.example/high.mp4",
  ]);
});

test("uses the existing media source when no usable variants are available", () => {
  expect(selectVideoSource("fallback.mp4", [], "high")).toBe("fallback.mp4");
  expect(selectVideoSource("fallback.mp4", [{ url: "", bitrate: null }], "low")).toBe(
    "fallback.mp4",
  );
});
