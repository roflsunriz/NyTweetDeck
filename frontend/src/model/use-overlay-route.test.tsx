import { afterEach, expect, mock, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

function Harness({ route, onClose }: { route: string; onClose: () => void }) {
  const close = useOverlayRoute(route, onClose);
  return (
    <button type="button" onClick={close}>
      close
    </button>
  );
}
