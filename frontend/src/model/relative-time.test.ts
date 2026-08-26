import { describe, expect, test } from "bun:test";
import { formatRelativeTime } from "./relative-time";

describe("relative post time", () => {
  test("formats against the current minute in the selected locale", () => {
    const now = Date.parse("2026-08-26T12:00:00Z");

    expect(formatRelativeTime("2026-08-26T11:55:00Z", "en", now)).toBe("5 minutes ago");
    expect(formatRelativeTime("2026-08-26T11:55:00Z", "ja", now)).toContain("5 分前");
    expect(formatRelativeTime("invalid", "en", now)).toBeNull();
  });
});
