import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { type AppLayout, layoutStorageKey, type StorageLike } from "./layout";
import { useSharedLayout } from "./use-shared-layout";

const originalFetch = globalThis.fetch;
const originalEventSource = globalThis.EventSource;
let sharedSnapshot: { revision: number; layout: AppLayout } | null;
let eventSources: FakeEventSource[];

describe("shared layout synchronization", () => {
  beforeEach(() => {
    sharedSnapshot = null;
    eventSources = [];
    globalThis.EventSource = class extends FakeEventSource {
      constructor() {
        super();
        eventSources.push(this);
      }
    } as unknown as typeof EventSource;
    globalThis.fetch = (async (input, init) => {
      if (!String(input).endsWith("/api/v1/settings/layout")) {
        return Response.json(null);
      }
      if (init?.method !== "PUT") {
        return sharedSnapshot === null
          ? new Response(null, { status: 204 })
          : Response.json(sharedSnapshot);
      }
      const request = JSON.parse(String(init.body)) as {
        expectedRevision: number;
        layout: AppLayout;
      };
      const revision = sharedSnapshot?.revision ?? 0;
      if (request.expectedRevision !== revision) {
        return Response.json(sharedSnapshot, { status: 409 });
      }
      sharedSnapshot = { revision: revision + 1, layout: request.layout };
      const event = { revision: sharedSnapshot.revision };
      queueMicrotask(() =>
        eventSources.forEach((source) => {
          source.emit(event);
        }),
      );
      return Response.json(sharedSnapshot);
    }) as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    globalThis.fetch = originalFetch;
    globalThis.EventSource = originalEventSource;
  });

  test("keeps open windows from different origin-local stores on one server revision", async () => {
    const firstStorage = new MemoryStorage();
    const secondStorage = new MemoryStorage();
    const user = userEvent.setup();
    render(
      <>
        <LayoutHarness id="first" storage={firstStorage} />
        <LayoutHarness id="second" storage={secondStorage} />
      </>,
    );

    await screen.findByText("first:system");
    await screen.findByText("second:system");
    await waitFor(() => expect(eventSources).toHaveLength(2));
    await user.click(screen.getByRole("button", { name: "first-dark" }));

    expect(await screen.findByText("first:dark")).toBeDefined();
    expect(await screen.findByText("second:dark")).toBeDefined();
    expect(sharedSnapshot?.layout.theme).toBe("dark");
    expect(firstStorage.getItem(layoutStorageKey)).toBeNull();
    expect(secondStorage.getItem(layoutStorageKey)).toBeNull();
  });

  test("automatically retries an initial shared-settings failure after the backend returns", async () => {
    const fetchAfterFailure = globalThis.fetch;
    let loadAttempts = 0;
    globalThis.fetch = (async (input, init) => {
      if (String(input).endsWith("/api/v1/settings/layout") && init?.method !== "PUT") {
        loadAttempts += 1;
        if (loadAttempts === 1) {
          return new Response(null, { status: 503 });
        }
      }
      return fetchAfterFailure(input, init);
    }) as typeof fetch;

    render(<LayoutHarness id="recovered" storage={new MemoryStorage()} />);

    expect(await screen.findByText("recovered:system", {}, { timeout: 2_000 })).toBeDefined();
    expect(loadAttempts).toBeGreaterThanOrEqual(2);
    expect(sharedSnapshot?.revision).toBe(1);
  });

  test("rebases a local column change onto a newer server revision instead of resetting it", async () => {
    const user = userEvent.setup();
    render(<LayoutHarness id="conflict" storage={new MemoryStorage()} />);
    await screen.findByText("conflict:system");
    if (sharedSnapshot === null) throw new Error("共有設定が初期化されていません。");
    sharedSnapshot = {
      revision: sharedSnapshot.revision + 1,
      layout: { ...sharedSnapshot.layout, theme: "dark" },
    };

    await user.click(screen.getByRole("button", { name: "conflict-column" }));

    await waitFor(() => expect(sharedSnapshot?.layout.columns).toHaveLength(1));
    expect(sharedSnapshot?.layout.theme).toBe("dark");
    expect(await screen.findByText("conflict:dark")).toBeDefined();
    expect(screen.getByText("conflict:columns:1")).toBeDefined();
  });

  test("does not apply a mutation twice when the save response is lost after commit", async () => {
    const user = userEvent.setup();
    render(<LayoutHarness id="lost-response" storage={new MemoryStorage()} />);
    await screen.findByText("lost-response:system");
    const serverFetch = globalThis.fetch;
    let droppedResponse = false;
    globalThis.fetch = (async (input, init) => {
      const response = await serverFetch(input, init);
      if (init?.method === "PUT" && !droppedResponse) {
        droppedResponse = true;
        throw new TypeError("response lost after commit");
      }
      return response;
    }) as typeof fetch;

    await user.click(screen.getByRole("button", { name: "lost-response-toggle" }));
    await waitFor(() => expect(sharedSnapshot?.layout.theme).toBe("dark"));
    await user.click(screen.getByRole("button", { name: "lost-response-retry" }));

    await waitFor(() => expect(screen.getByText("lost-response:dark")).toBeDefined());
    expect(sharedSnapshot?.layout.theme).toBe("dark");
  });
});

function LayoutHarness({ id, storage }: { id: string; storage: StorageLike }) {
  const { layout, setLayout, retry } = useSharedLayout(storage);
  if (layout === null) return <span>{id}:loading</span>;
  return (
    <div>
      <span>{`${id}:${layout.theme}`}</span>
      <span>{`${id}:columns:${layout.columns.length}`}</span>
      <button
        type="button"
        aria-label={`${id}-dark`}
        onClick={() => setLayout((current) => ({ ...current, theme: "dark" }))}
      />
      <button
        type="button"
        aria-label={`${id}-column`}
        onClick={() =>
          setLayout((current) => ({
            ...current,
            columns: current.columns.some((column) => column.id === `${id}-home`)
              ? current.columns
              : [...current.columns, { id: `${id}-home`, kind: "home", target: null, label: null }],
          }))
        }
      />
      <button
        type="button"
        aria-label={`${id}-toggle`}
        onClick={() =>
          setLayout((current) => ({
            ...current,
            theme: current.theme === "dark" ? "light" : "dark",
          }))
        }
      />
      <button type="button" aria-label={`${id}-retry`} onClick={retry} />
    </div>
  );
}

class MemoryStorage implements StorageLike {
  private readonly entries = new Map<string, string>();

  getItem(key: string): string | null {
    return this.entries.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.entries.set(key, value);
  }

  removeItem(key: string): void {
    this.entries.delete(key);
  }
}

class FakeEventSource {
  private listener: ((event: MessageEvent<string>) => void) | null = null;

  addEventListener(_type: string, listener: EventListenerOrEventListenerObject | null): void {
    if (typeof listener === "function") {
      this.listener = listener as (event: MessageEvent<string>) => void;
    }
  }

  removeEventListener(): void {
    this.listener = null;
  }

  close(): void {}

  emit(value: object): void {
    this.listener?.(new MessageEvent("layout-settings-update", { data: JSON.stringify(value) }));
  }
}
