import { resolve } from "node:path";

interface CdpTarget {
  type: string;
  webSocketDebuggerUrl: string;
}

interface CdpMessage {
  id?: number;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { message: string };
}

interface PendingCall {
  resolve: (value: unknown) => void;
  reject: (reason: Error) => void;
}

class CdpClient {
  private readonly socket: WebSocket;
  private nextId = 1;
  private readonly pending = new Map<number, PendingCall>();
  private readonly eventWaiters = new Map<string, Array<(params: unknown) => void>>();
  private readonly eventListeners = new Map<string, Array<(params: unknown) => void>>();

  private constructor(socket: WebSocket) {
    this.socket = socket;
    this.socket.addEventListener("message", (event) => {
      const message = JSON.parse(String(event.data)) as CdpMessage;
      if (message.id !== undefined) {
        const call = this.pending.get(message.id);
        if (call === undefined) {
          return;
        }
        this.pending.delete(message.id);
        if (message.error !== undefined) {
          call.reject(new Error(message.error.message));
        } else {
          call.resolve(message.result);
        }
        return;
      }

      if (message.method !== undefined) {
        for (const listener of this.eventListeners.get(message.method) ?? []) {
          listener(message.params);
        }
        const waiters = this.eventWaiters.get(message.method) ?? [];
        this.eventWaiters.delete(message.method);
        for (const waiter of waiters) {
          waiter(message.params);
        }
      }
    });
  }

  static async connect(url: string): Promise<CdpClient> {
    const socket = new WebSocket(url);
    await new Promise<void>((resolveOpen, rejectOpen) => {
      socket.addEventListener("open", () => resolveOpen(), { once: true });
      socket.addEventListener("error", () => rejectOpen(new Error("CDP connection failed.")), {
        once: true,
      });
    });
    return new CdpClient(socket);
  }

  call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    const id = this.nextId++;
    return new Promise<T>((resolveCall, rejectCall) => {
      this.pending.set(id, {
        resolve: (value) => resolveCall(value as T),
        reject: rejectCall,
      });
      this.socket.send(JSON.stringify({ id, method, params }));
    });
  }

  waitForEvent(method: string, timeoutMilliseconds = 10_000): Promise<unknown> {
    return new Promise((resolveEvent, rejectEvent) => {
      const timeout = setTimeout(
        () => rejectEvent(new Error(`Timed out waiting for ${method}.`)),
        timeoutMilliseconds,
      );
      const waiters = this.eventWaiters.get(method) ?? [];
      waiters.push((params) => {
        clearTimeout(timeout);
        resolveEvent(params);
      });
      this.eventWaiters.set(method, waiters);
    });
  }

  on(method: string, listener: (params: unknown) => void): void {
    const listeners = this.eventListeners.get(method) ?? [];
    listeners.push(listener);
    this.eventListeners.set(method, listeners);
  }

  async evaluate<T>(expression: string): Promise<T> {
    const response = await this.call<{
      result: { value?: T };
      exceptionDetails?: { text: string };
    }>("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    if (response.exceptionDetails !== undefined) {
      throw new Error(response.exceptionDetails.text);
    }
    return response.result.value as T;
  }

  close(): void {
    this.socket.close();
  }
}

const applicationUrl = process.env.NYTWEETDECK_URL ?? "http://127.0.0.1:18080";
const cdpPort = process.env.CHROME_CDP_PORT ?? "9222";
const targets = (await fetch(`http://127.0.0.1:${cdpPort}/json/list`).then((response) =>
  response.json(),
)) as CdpTarget[];
const page = targets.find((target) => target.type === "page");
if (page === undefined) {
  throw new Error("Chromeの検証用ページが見つかりません。");
}

const client = await CdpClient.connect(page.webSocketDebuggerUrl);
await client.call("Page.enable");
await client.call("Runtime.enable");
await client.call("Log.enable");

const browserErrors: string[] = [];
client.on("Runtime.exceptionThrown", (params) => browserErrors.push(JSON.stringify(params)));
client.on("Runtime.consoleAPICalled", (params) => {
  const event = params as { type?: string };
  if (event.type === "error" || event.type === "assert") {
    browserErrors.push(JSON.stringify(params));
  }
});
client.on("Log.entryAdded", (params) => {
  const event = params as { entry?: { level?: string } };
  if (event.entry?.level === "error") {
    browserErrors.push(JSON.stringify(params));
  }
});

async function navigate(): Promise<void> {
  const loaded = client.waitForEvent("Page.loadEventFired");
  await client.call("Page.navigate", { url: applicationUrl });
  await loaded;
}

async function reload(): Promise<void> {
  const loaded = client.waitForEvent("Page.loadEventFired");
  await client.call("Page.reload", { ignoreCache: true });
  await loaded;
}

async function waitForCondition(expression: string, timeoutMilliseconds = 3_000): Promise<void> {
  const deadline = Date.now() + timeoutMilliseconds;
  while (Date.now() < deadline) {
    if (await client.evaluate<boolean>(expression)) {
      return;
    }
    await Bun.sleep(40);
  }
  throw new Error(`DOM状態の待機がタイムアウトしました: ${expression}`);
}

await navigate();
await client.evaluate("localStorage.clear()");
await reload();

const viewports = [
  { width: 1440, height: 900, columns: 3 },
  { width: 768, height: 1024, columns: 2 },
  { width: 390, height: 844, columns: 1 },
] as const;

const results: Array<Record<string, unknown>> = [];
for (const viewport of viewports) {
  await client.call("Emulation.setDeviceMetricsOverride", {
    width: viewport.width,
    height: viewport.height,
    deviceScaleFactor: 1,
    mobile: viewport.width <= 390,
  });
  await client.evaluate("localStorage.clear()");
  await reload();

  for (let index = 0; index < viewport.columns; index += 1) {
    const clicked = await client.evaluate<boolean>(`(() => {
      const button = [...document.querySelectorAll("button")]
        .find((candidate) => candidate.getAttribute("aria-label") === "カラムを追加");
      if (!(button instanceof HTMLButtonElement)) return false;
      button.click();
      return true;
    })()`);
    if (!clicked) {
      throw new Error("カラム追加ダイアログを開けませんでした。");
    }
    await waitForCondition("document.querySelector('[role=\"dialog\"]') !== null");
    const added = await client.evaluate<boolean>(`(() => {
      const button = document.querySelector(".column-type-card");
      if (!(button instanceof HTMLButtonElement)) return false;
      button.click();
      return true;
    })()`);
    if (!added) {
      throw new Error("カラムを追加できませんでした。");
    }
    await waitForCondition(`document.querySelectorAll(".deck-column").length === ${index + 1}`);
  }

  const metrics = await client.evaluate<Record<string, unknown>>(`({
    viewport: { width: innerWidth, height: innerHeight },
    documentWidth: document.documentElement.scrollWidth,
    bodyWidth: document.body.getBoundingClientRect().width,
    columnCount: document.querySelectorAll(".deck-column").length,
    dialogCount: document.querySelectorAll('[role="dialog"]').length,
    horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
    interactiveElements: document.querySelectorAll("button, select, a, input").length
  })`);

  const screenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
    format: "png",
    fromSurface: true,
  });
  const screenshotPath = resolve(
    import.meta.dir,
    `../../target/ui-${viewport.width}x${viewport.height}.png`,
  );
  await Bun.write(screenshotPath, Buffer.from(screenshot.data, "base64"));

  await reload();
  const persistedColumns = await client.evaluate<number>(
    'document.querySelectorAll(".deck-column").length',
  );
  if (persistedColumns !== viewport.columns) {
    throw new Error(`カラム永続化に失敗しました: ${persistedColumns}/${viewport.columns}`);
  }

  results.push({ ...metrics, persistedColumns, screenshotPath });
}

console.info(JSON.stringify(results, null, 2));
if (browserErrors.length > 0) {
  throw new Error(`ブラウザエラーを検出しました:\n${browserErrors.join("\n")}`);
}
client.close();
