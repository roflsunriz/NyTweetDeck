import { afterEach, expect, jest, test } from "bun:test";
import { act, cleanup, renderHook } from "@testing-library/react";
import { useState } from "react";
import { type AppLayout, createDefaultLayout } from "./layout";
import { useColumnUndo } from "./use-column-undo";

afterEach(() => {
  cleanup();
  jest.useRealTimers();
});
function useHarness() {
  const [layout, setLayout] = useState<AppLayout>({
    ...createDefaultLayout(),
    columns: ["a", "b", "c"].map((id) => ({
      id,
      kind: "search" as const,
      target: id,
      label: id,
      sort: "top" as const,
    })),
  });
  return { layout, ...useColumnUndo(layout, (update) => setLayout((current) => update(current))) };
}
test("multiple removals undo once at original positions with configuration intact", () => {
  const { result } = renderHook(useHarness);
  act(() => result.current.remove("b"));
  act(() => result.current.remove("c"));
  expect(result.current.layout.columns.map((column) => column.id)).toEqual(["a"]);
  act(() => {
    result.current.undo("b");
    result.current.undo("b");
  });
  act(() => result.current.undo("c"));
  expect(result.current.layout.columns.map((column) => column.id)).toEqual(["a", "b", "c"]);
  expect(result.current.layout.columns[1]?.sort).toBe("top");
});
test("the undo action expires after ten seconds", () => {
  jest.useFakeTimers();
  const { result } = renderHook(useHarness);
  act(() => result.current.remove("b"));
  act(() => jest.advanceTimersByTime(9_999));
  expect(result.current.removed).toHaveLength(1);
  act(() => jest.advanceTimersByTime(1));
  expect(result.current.removed).toHaveLength(0);
  act(() => result.current.undo("b"));
  expect(result.current.layout.columns).toHaveLength(2);
});

test("consecutive removals at the front restore correctly in either order", () => {
  for (const order of [
    ["a", "b"],
    ["b", "a"],
  ]) {
    const { result, unmount } = renderHook(useHarness);
    act(() => result.current.remove("a"));
    act(() => result.current.remove("b"));
    for (const id of order) act(() => result.current.undo(id));
    expect(result.current.layout.columns.map((column) => column.id)).toEqual(["a", "b", "c"]);
    unmount();
  }
});
