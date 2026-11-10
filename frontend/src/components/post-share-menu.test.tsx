import { afterEach, expect, mock, spyOn, test } from "bun:test";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { ShareMenu } from "./post-share-menu";

const originalWidth = Object.getOwnPropertyDescriptor(window, "innerWidth");
const originalHeight = Object.getOwnPropertyDescriptor(window, "innerHeight");
afterEach(() => {
  cleanup();
  mock.restore();
  if (originalWidth) Object.defineProperty(window, "innerWidth", originalWidth);
  if (originalHeight) Object.defineProperty(window, "innerHeight", originalHeight);
  document.documentElement.dir = "";
});

function renderMenu(onCopy = mock(() => {})) {
  return {
    onCopy,
    ...render(
      <div style={{ overflow: "hidden", height: 80 }}>
        <ShareMenu
          label="share"
          urlLabel="url"
          detailsLabel="details"
          translatedDetailsLabel="translated"
          onCopyUrl={onCopy}
          onCopyDetails={onCopy}
          onCopyTranslatedDetails={onCopy}
          extraActions={[{ id: "share-media", label: "all media", run: async () => onCopy() }]}
        />
      </div>,
    ),
  };
}

test("escapes column clipping, supports keyboard actions, and restores trigger focus", () => {
  const { container, onCopy } = renderMenu();
  const trigger = screen.getByRole("button", { name: "share" });
  fireEvent.click(trigger);
  const menu = screen.getByRole("menu");
  expect(menu.parentElement).toBe(document.body);
  expect(container.contains(menu)).toBe(false);
  expect(document.activeElement?.textContent).toBe("url");
  fireEvent.keyDown(menu, { key: "End" });
  expect(document.activeElement?.textContent).toBe("all media");
  fireEvent.keyDown(menu, { key: "ArrowDown" });
  expect(document.activeElement?.textContent).toBe("url");
  fireEvent.keyDown(menu, { key: "ArrowUp" });
  expect(document.activeElement?.textContent).toBe("all media");
  fireEvent.click(screen.getByRole("menuitem", { name: "all media" }));
  expect(onCopy).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole("menu")).toBeNull();
  expect(document.activeElement).toBe(trigger);
});

test("Escape closes the menu before its parent dialog and outside pointers dismiss it", () => {
  renderMenu();
  const parentEscape = mock(() => {});
  document.addEventListener("keydown", parentEscape);
  try {
    fireEvent.click(screen.getByRole("button", { name: "share" }));
    fireEvent.keyDown(document.activeElement ?? document, { key: "Escape" });
    expect(parentEscape).not.toHaveBeenCalled();
    expect(screen.queryByRole("menu")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "share" }));
    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole("menu")).toBeNull();
  } finally {
    document.removeEventListener("keydown", parentEscape);
  }
});

for (const scenario of [
  { width: 390, height: 320, direction: "ltr", triggerTop: 20 },
  { width: 390, height: 320, direction: "rtl", triggerTop: 20 },
  { width: 180, height: 120, direction: "rtl", triggerTop: 52 },
]) {
  test(`keeps all actions scrollable inside ${scenario.width}x${scenario.height} ${scenario.direction}`, () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: scenario.width });
    Object.defineProperty(window, "innerHeight", { configurable: true, value: scenario.height });
    document.documentElement.dir = scenario.direction;
    spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (
      this: HTMLElement,
    ) {
      if (this.classList.contains("post-share-menu"))
        return new DOMRect(
          0,
          0,
          Math.min(352, Number.parseFloat(this.style.maxWidth)),
          Math.min(260, Number.parseFloat(this.style.maxHeight)),
        );
      return new DOMRect(20, scenario.triggerTop, 28, 28);
    });
    renderMenu();
    fireEvent.click(screen.getByRole("button", { name: "share" }));
    const menu = screen.getByRole("menu");
    const left = Number.parseFloat(menu.style.left);
    const top = Number.parseFloat(menu.style.top);
    const width = menu.getBoundingClientRect().width;
    const height = Number.parseFloat(menu.style.maxHeight);
    expect(left).toBeGreaterThanOrEqual(8);
    expect(top).toBeGreaterThanOrEqual(8);
    expect(left + width).toBeLessThanOrEqual(scenario.width - 8);
    expect(top + height).toBeLessThanOrEqual(scenario.height - 8);
    expect(screen.getAllByRole("menuitem")).toHaveLength(4);
  });
}
