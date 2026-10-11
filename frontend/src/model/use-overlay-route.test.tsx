import { afterEach, expect, mock, spyOn, test } from "bun:test";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StrictMode } from "react";
import { useOverlayRoute } from "./use-overlay-route";

afterEach(() => {
  cleanup();
  window.history.replaceState(null, "", "/");
});

test("pushes an overlay hash and closes through browser Back", async () => {
  const onClose = mock(() => undefined);
  render(<Harness route="post/100" onClose={onClose} />);
  await waitFor(() =>
    expect(String((window.history.state as Record<string, unknown>).nytdOverlayToken)).toContain(
      "nytd-overlay-",
    ),
  );

  window.history.back();
  await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
});

test("StrictMode effect replay creates one history entry and Back returns to the parent", async () => {
  window.history.replaceState(
    { nytdOverlayToken: "parent", nytdPostDetailIndex: 3 },
    "",
    "#/post/third",
  );
  const length = window.history.length;
  const onClose = mock(() => undefined);
  render(
    <StrictMode>
      <Harness route="compose" onClose={onClose} />
    </StrictMode>,
  );
  expect(window.history.length).toBe(length + 1);
  window.history.back();
  await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  expect(window.location.hash).toBe("#/post/third");
  expect(window.history.state.nytdOverlayToken).toBe("parent");
  expect(window.history.state.nytdPostDetailIndex).toBe(3);
});

test("close action returns to the previous route exactly once", async () => {
  const onClose = mock(() => undefined);
  const user = userEvent.setup();
  render(<Harness route="user/42" onClose={onClose} />);
  await waitFor(() => expect(window.history.state).not.toBeNull());

  await user.click(screen.getByRole("button", { name: "close" }));
  expect(onClose).toHaveBeenCalledTimes(1);
});

test("keeps ancestor overlays open when returning between descendant routes", async () => {
  const parentClose = mock(() => undefined);
  const childClose = mock(() => undefined);
  const grandchildClose = mock(() => undefined);
  render(<Harness route="user/42" onClose={parentClose} />);
  render(<Harness route="post/100" onClose={childClose} />);
  render(<Harness route="compose" onClose={grandchildClose} />);
  window.history.back();
  await waitFor(() => expect(grandchildClose).toHaveBeenCalledTimes(1));
  expect(parentClose).not.toHaveBeenCalled();
  expect(childClose).not.toHaveBeenCalled();
});

test("keeps the composer until Back restores the parent, so the next navigation uses its entry", () => {
  const parentState = { nytdOverlayToken: "parent-post", nytdPostDetailIndex: 3 };
  window.history.replaceState(parentState, "", "#/post/third-reply");
  const onClose = mock(() => {
    expect(window.history.state.nytdOverlayToken).toBe("parent-post");
    expect(window.location.hash).toBe("#/post/third-reply");
  });
  const back = spyOn(window.history, "back").mockImplementation(() => {});
  try {
    render(<Harness route="compose" onClose={onClose} />);
    fireEvent.click(screen.getByRole("button"));
    expect(back).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
    act(() => {
      window.history.replaceState(parentState, "", "#/post/third-reply");
      window.dispatchEvent(new PopStateEvent("popstate", { state: parentState }));
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  } finally {
    back.mockRestore();
  }
});

function Harness({ route, onClose }: { route: string; onClose: () => void }) {
  const close = useOverlayRoute(route, onClose);
  return (
    <button type="button" onClick={close}>
      close
    </button>
  );
}
