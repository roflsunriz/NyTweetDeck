import { afterEach, expect, mock, test } from "bun:test";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { ImageViewer } from "./image-viewer";

afterEach(cleanup);

const sources = [
  "https://pbs.twimg.com/media/first.jpg?format=jpg&name=small",
  "https://pbs.twimg.com/media/second.jpg?format=jpg&name=small",
  "https://pbs.twimg.com/media/third.jpg?format=jpg&name=small",
] as const;

test("moves only while the primary pointer is held and preserves exact pan coordinates", () => {
  renderViewer();
  const { image, viewport } = viewerElements();

  fireEvent(viewport, pointerEvent("pointerdown", 40, 50, 1));
  fireEvent(viewport, pointerEvent("pointermove", 70, 75, 1));
  fireEvent(viewport, pointerEvent("pointermove", 90, 85, 1));
  expect(image.style.transform).toContain("translate(50px, 35px)");
  expect(viewport.dataset.dragging).toBe("true");

  fireEvent(viewport, pointerEvent("pointerup", 90, 85, 0));
  fireEvent(viewport, pointerEvent("pointermove", 140, 120, 1));
  expect(image.style.transform).toContain("translate(50px, 35px)");
  expect(viewport.dataset.dragging).toBe("false");

  fireEvent(viewport, pointerEvent("pointerdown", 140, 120, 1, 2));
  fireEvent(viewport, pointerEvent("pointermove", 170, 150, 1, 3));
  expect(image.style.transform).toContain("translate(50px, 35px)");
  fireEvent(viewport, pointerEvent("pointercancel", 140, 120, 0, 2));
  fireEvent(viewport, pointerEvent("pointermove", 180, 160, 1, 2));
  expect(image.style.transform).toContain("translate(50px, 35px)");
});

test("supports toolbar, wheel, double-click, and keyboard zoom/reset operations", async () => {
  renderViewer();
  const user = userEvent.setup();
  const { viewport } = viewerElements();

  await user.click(screen.getByRole("button", { name: "拡大" }));
  expect(Number(viewport.dataset.zoom)).toBeGreaterThan(1);
  await user.click(screen.getByRole("button", { name: "縮小" }));
  expect(Number(viewport.dataset.zoom)).toBe(1);
  fireEvent.wheel(viewport, { deltaY: 400, clientX: 100, clientY: 100 });
  expect(Number(viewport.dataset.zoom)).toBeLessThan(1);
  fireEvent.doubleClick(viewport);
  expect(Number(viewport.dataset.zoom)).toBe(1);

  await user.keyboard("+");
  expect(Number(viewport.dataset.zoom)).toBeGreaterThan(1);
  await user.keyboard("-");
  expect(Number(viewport.dataset.zoom)).toBe(1);
  fireEvent(viewport, pointerEvent("pointerdown", 40, 40, 1));
  fireEvent(viewport, pointerEvent("pointermove", 65, 55, 1));
  await user.keyboard("0");
  expect(viewport.dataset.zoom).toBe("1");
  expect(viewport.dataset.panX).toBe("0");
  expect(viewport.dataset.panY).toBe("0");
  fireEvent(viewport, pointerEvent("pointermove", 85, 75, 1));
  expect(viewport.dataset.panX).toBe("0");
});

for (const count of [1, 2, 4]) {
  test(`bounds ${count} images for drag, buttons and keys from the last image`, async () => {
    const siblings = [...sources, "https://pbs.twimg.com/media/fourth.jpg"].slice(0, count);
    renderViewer({ src: siblings[count - 1], sources: siblings });
    const user = userEvent.setup();
    const { viewport } = viewerElements();
    mockViewportBounds(viewport, 0, 300);
    const swipe = (x: number) => {
      fireEvent(viewport, pointerEvent("pointerdown", 150, 100, 1));
      fireEvent(viewport, pointerEvent("pointermove", x, 100, 1));
      fireEvent(viewport, pointerEvent("pointerup", x, 100, 0));
    };
    const buttons = screen.getAllByRole("button");
    expect(viewport.dataset.imageCount).toBe(String(count));
    expect(viewport.dataset.imageIndex).toBe(String(count - 1));
    await user.keyboard("+");
    const zoom = viewport.dataset.zoom;
    await user.keyboard("{ArrowRight}");
    expect(viewport.dataset.zoom).toBe(zoom);
    swipe(0);
    expect(viewport.dataset.imageIndex).toBe(String(count - 1));
    if (count > 1) {
      const previous = buttons[0] as HTMLButtonElement;
      const next = buttons[1] as HTMLButtonElement;
      expect(next.disabled).toBe(true);
      await user.click(next);
      expect(viewport.dataset.imageIndex).toBe(String(count - 1));
      for (let index = count - 2; index >= 0; index--) {
        swipe(300);
        expect(viewport.dataset.imageIndex).toBe(String(index));
        expect(viewport.dataset.zoom).toBe("1");
      }
      expect(previous.disabled).toBe(true);
      await user.click(previous);
      await user.keyboard("{ArrowLeft}");
      swipe(300);
      expect(viewport.dataset.imageIndex).toBe("0");
      await user.click(next);
      expect(viewport.dataset.imageIndex).toBe("1");
      await user.keyboard("{ArrowLeft}");
      expect(viewport.dataset.imageIndex).toBe("0");
      for (let index = 1; index < count; index++) {
        swipe(0);
        expect(viewport.dataset.imageIndex).toBe(String(index));
      }
      await user.click(previous);
      expect(viewport.dataset.imageIndex).toBe(String(count - 2));
      await user.keyboard("{ArrowRight}");
      expect(viewport.dataset.imageIndex).toBe(String(count - 1));
    } else {
      await user.keyboard("{ArrowLeft}");
      swipe(300);
      expect(viewport.dataset.imageIndex).toBe("0");
      expect(buttons).toHaveLength(4);
    }
    expect(viewport.querySelectorAll("img")).toHaveLength(1);
  });
}

test("cancels an active drag when zoom/reset wins and closes exactly once with Escape", async () => {
  const onClose = mock(() => undefined);
  renderViewer({ onClose });
  const user = userEvent.setup();
  const { viewport } = viewerElements();

  fireEvent(viewport, pointerEvent("pointerdown", 40, 40, 1));
  fireEvent.wheel(viewport, { deltaY: -100, clientX: 100, clientY: 100 });
  fireEvent(viewport, pointerEvent("pointermove", 80, 80, 1));
  expect(viewport.dataset.panX).not.toBe("40");
  expect(viewport.dataset.dragging).toBe("false");

  await user.keyboard("{Escape}");
  expect(onClose).toHaveBeenCalledTimes(1);
});

test("uses a modal dialog on wide screens and a non-modal full-page region on phone widths", () => {
  const originalMatchMedia = window.matchMedia;
  try {
    window.matchMedia = matchMediaResult(true);
    renderViewer();
    expect(screen.queryByRole("dialog", { name: "画像をフルサイズで表示" })).toBeNull();
    const region = screen.getByRole("region", { name: "画像をフルサイズで表示" });
    expect(region.getAttribute("aria-modal")).toBeNull();
    expect(region.getAttribute("data-presentation")).toBe("full-page");
  } finally {
    cleanup();
    window.matchMedia = originalMatchMedia;
  }
});

test("zooms with two-finger pinch while keeping the pinch center anchored and clamps to limits", () => {
  renderViewer();
  const { image, viewport } = viewerElements();
  mockViewportBounds(viewport, 0, 300);

  fireEvent(viewport, touchPointerEvent("pointerdown", 100, 100, 1, true));
  fireEvent(viewport, touchPointerEvent("pointerdown", 200, 100, 2, false));
  expect(Number(viewport.dataset.zoom)).toBe(1);

  // Spread fingers: distance 100 -> 120, ratio 1.2
  fireEvent(viewport, touchPointerEvent("pointermove", 80, 100, 1, true));
  expect(Number(viewport.dataset.zoom)).toBeCloseTo(1.2, 2);
  expect(image.style.transform).toContain("scale(1.2)");
  // New center 140 with viewport center 150, anchored pan should be -10
  expect(Number(viewport.dataset.panX)).toBeCloseTo(-10, 0);
  expect(viewport.dataset.dragging).toBe("false");

  // Pinch further out until max clamp, then move until min clamp
  fireEvent(viewport, touchPointerEvent("pointermove", 20, 100, 1, true));
  expect(Number(viewport.dataset.zoom)).toBeLessThanOrEqual(8);
  // Lift one finger: pinch ends, remaining finger should be able to drag again
  fireEvent(viewport, touchPointerEvent("pointerup", 20, 100, 1, true));
  fireEvent(viewport, touchPointerEvent("pointermove", 210, 110, 2, false));
  expect(viewport.dataset.dragging).toBe("true");
  expect(image.style.transform).toContain("translate(");
});

function renderViewer({
  src = sources[0],
  sources: siblingSources = [src],
  onClose = () => undefined,
}: {
  src?: string;
  sources?: readonly string[];
  onClose?: () => void;
} = {}) {
  return render(
    <ImageViewer
      src={src}
      sources={siblingSources}
      translation={translate("ja")}
      onClose={onClose}
    />,
  );
}

function viewerElements() {
  const viewer = screen.getByRole("dialog", { name: "画像をフルサイズで表示" });
  const viewport = viewer.querySelector(".image-viewer-viewport");
  const image = viewer.querySelector("img");
  if (!(viewport instanceof HTMLElement) || !(image instanceof HTMLImageElement)) {
    throw new Error("画像ビューアーの要素がありません。");
  }
  return { image, viewport };
}

function mockViewportBounds(viewport: HTMLElement, left: number, right: number) {
  viewport.getBoundingClientRect = () => ({
    x: left,
    y: 0,
    left,
    right,
    top: 0,
    bottom: 200,
    width: right - left,
    height: 200,
    toJSON: () => ({}),
  });
}

function pointerEvent(
  type: string,
  clientX: number,
  clientY: number,
  buttons: number,
  pointerId = 1,
): Event {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperties(event, {
    pointerId: { value: pointerId },
    pointerType: { value: "mouse" },
    isPrimary: { value: true },
    button: { value: 0 },
    buttons: { value: buttons },
    clientX: { value: clientX },
    clientY: { value: clientY },
  });
  return event;
}

function touchPointerEvent(
  type: string,
  clientX: number,
  clientY: number,
  pointerId: number,
  isPrimary: boolean,
): Event {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperties(event, {
    pointerId: { value: pointerId },
    pointerType: { value: "touch" },
    isPrimary: { value: isPrimary },
    button: { value: 0 },
    buttons: { value: type === "pointerup" ? 0 : 1 },
    clientX: { value: clientX },
    clientY: { value: clientY },
  });
  return event;
}

function matchMediaResult(matches: boolean): typeof window.matchMedia {
  return ((query: string) => ({
    addEventListener: () => undefined,
    addListener: () => undefined,
    dispatchEvent: () => true,
    matches,
    media: query,
    onchange: null,
    removeEventListener: () => undefined,
    removeListener: () => undefined,
  })) as typeof window.matchMedia;
}
