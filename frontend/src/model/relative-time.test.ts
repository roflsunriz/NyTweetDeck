import { describe, expect, test } from "bun:test";
import { formatAbsolutePostTime, formatRelativeTime } from "./relative-time";

describe("relative post time", () => {
  test("full dates retain the year, seconds and time zone for old posts", () => {
    const formatted = formatAbsolutePostTime("2020-07-15T12:34:56Z", "en");
    expect(formatted).toContain("2020");
    expect(formatted).toContain(":56");
    expect(formatted).toMatch(/GMT|UTC|[A-Z]{2,5}$/u);
    expect(formatAbsolutePostTime("invalid", "ja")).toBeNull();
    expect(formatAbsolutePostTime(null, "ja")).toBeNull();
  });
  test("formats against the current minute in the selected locale", () => {
    const now = Date.parse("2026-08-26T12:00:00Z");

    expect(formatRelativeTime("2026-08-26T11:55:00Z", "en", now)).toBe("5 minutes ago");
    expect(formatRelativeTime("2026-08-26T11:55:00Z", "ja", now)).toContain("5 分前");
    expect(formatRelativeTime("invalid", "en", now)).toBeNull();
  });
});
