import { expect, test } from "bun:test";
import { TranslationMemory, translationMemoryKey } from "./translation-memory";

test("shares pending work, stores only success and evicts least recently used successes", async () => {
  const memory = new TranslationMemory<string>(2);
  let calls = 0;
  let finish: (value: string) => void = () => {};
  const operation = () => {
    calls++;
    return new Promise<string>((resolve) => {
      finish = resolve;
    });
  };
  const first = memory.load("first", operation, Boolean);
  const joined = memory.load("first", operation, Boolean);
  expect(first).toBe(joined);
  await Promise.resolve();
  finish("one");
  await first;
  expect(calls).toBe(1);
  await memory.load("second", async () => "two", Boolean);
  expect(memory.get("first")).toBe("one");
  await memory.load("third", async () => "three", Boolean);
  expect(memory.get("second")).toBeUndefined();
  expect(memory.get("first")).toBe("one");
  await memory.load("missing", async () => "", Boolean);
  expect(memory.get("missing")).toBeUndefined();
  await expect(
    memory.load(
      "failed",
      async () => {
        throw new Error("failed");
      },
      Boolean,
    ),
  ).rejects.toThrow("failed");
  expect(await memory.load("failed", async () => "recovered", Boolean)).toBe("recovered");
});

test("separates every translation scope including text revisions", () => {
  const scope = {
    accountId: "account",
    kind: "post" as const,
    id: "123",
    sourceLanguage: "en",
    targetLanguage: "ja",
    text: "Original",
  };
  const original = translationMemoryKey(scope);
  for (const changed of [
    { accountId: "another" },
    { kind: "note" as const },
    { id: "124" },
    { sourceLanguage: "fr" },
    { targetLanguage: "de" },
    { text: "Edited" },
  ])
    expect(translationMemoryKey({ ...scope, ...changed })).not.toBe(original);
});
