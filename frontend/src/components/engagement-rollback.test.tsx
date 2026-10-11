import { afterEach, expect, jest, test } from "bun:test";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useEffect, useState } from "react";
import { useOptimisticToggle } from "./use-optimistic-toggle";

afterEach(() => {
  cleanup();
  jest.useRealTimers();
});

function Toggle({
  request,
  error,
  actionKey = "like",
  confirmedActive,
}: {
  request: (action: string) => Promise<void>;
  error: (error: unknown) => void;
  actionKey?: string;
  confirmedActive?: boolean;
}) {
  const [active, setActive] = useState(false);
  const [count, setCount] = useState(3);
  const { toggle, reconcile } = useOptimisticToggle(request, error);
  useEffect(() => {
    if (confirmedActive !== undefined)
      reconcile(actionKey, confirmedActive, confirmedActive ? 4 : 3);
  }, [actionKey, confirmedActive, reconcile]);
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={() =>
        toggle({
          actionKey,
          active,
          count,
          setActive,
          setCount,
          enableAction: "on",
          disableAction: "off",
        })
      }
    >
      {count}
    </button>
  );
}

test("an accepted live update cancels a rejected response before a fresh inverse action", async () => {
  jest.useFakeTimers();
  let reject: ((error: Error) => void) | undefined;
  let calls = 0;
  const request = async () => {
    if (++calls === 1)
      await new Promise<void>((_, fail) => {
        reject = fail;
      });
  };
  const errors = jest.fn();
  const view = render(<Toggle request={request} error={errors} />);
  const button = screen.getByRole("button");
  fireEvent.click(button);
  view.rerender(<Toggle request={request} error={errors} confirmedActive />);
  await act(async () => reject?.(new Error("ambiguous response")));
  await act(async () => fireEvent.click(button));
  expect(button.getAttribute("aria-pressed")).toBe("false");
  act(() => jest.advanceTimersByTime(30_000));
  expect(button.getAttribute("aria-pressed")).toBe("false");
  expect(errors).not.toHaveBeenCalled();
});

test("a late rejection rolls back at thirty seconds from the last click", async () => {
  jest.useFakeTimers();
  let reject: ((error: Error) => void) | undefined;
  const errors = jest.fn();
  render(
    <Toggle
      error={errors}
      request={() =>
        new Promise((_, fail) => {
          reject = fail;
        })
      }
    />,
  );
  const button = screen.getByRole("button");
  fireEvent.click(button);
  act(() => jest.advanceTimersByTime(20_000));
  await act(async () => reject?.(new Error("rejected")));
  act(() => jest.advanceTimersByTime(9_999));
  expect(button.getAttribute("aria-pressed")).toBe("true");
  expect(errors).not.toHaveBeenCalled();
  act(() => jest.advanceTimersByTime(1));
  expect(button.getAttribute("aria-pressed")).toBe("false");
  expect(button.textContent).toBe("3");
  expect(errors).toHaveBeenCalledTimes(1);
});

test("a new accepted intent cancels the rejected intent's rollback", async () => {
  jest.useFakeTimers();
  const errors = jest.fn();
  let calls = 0;
  render(
    <Toggle
      error={errors}
      request={async () => {
        if (++calls === 1) throw new Error("rejected");
      }}
    />,
  );
  const button = screen.getByRole("button");
  await act(async () => fireEvent.click(button));
  act(() => jest.advanceTimersByTime(10_000));
  await act(async () => fireEvent.click(button));
  await act(async () => fireEvent.click(button));
  expect(button.getAttribute("aria-pressed")).toBe("true");
  act(() => jest.advanceTimersByTime(30_000));
  expect(button.getAttribute("aria-pressed")).toBe("true");
  expect(errors).not.toHaveBeenCalled();
});

test("unmount cancels delayed errors and bookmark rejection stays immediate", async () => {
  jest.useFakeTimers();
  const errors = jest.fn();
  const view = render(
    <Toggle
      error={errors}
      request={async () => {
        throw new Error("rejected");
      }}
    />,
  );
  await act(async () => fireEvent.click(screen.getByRole("button")));
  view.unmount();
  act(() => jest.advanceTimersByTime(30_000));
  expect(errors).not.toHaveBeenCalled();
  render(
    <Toggle
      actionKey="bookmark"
      error={errors}
      request={async () => {
        throw new Error("rejected");
      }}
    />,
  );
  await act(async () => fireEvent.click(screen.getByRole("button")));
  expect(screen.getByRole("button").getAttribute("aria-pressed")).toBe("false");
  expect(errors).toHaveBeenCalledTimes(1);
});
