export interface CdpTarget {
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

export class CdpClient {
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
        if (call === undefined) return;
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
        for (const waiter of waiters) waiter(message.params);
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
    if (response.exceptionDetails !== undefined) throw new Error(response.exceptionDetails.text);
    return response.result.value as T;
  }

  close(): void {
    this.socket.close();
  }
}
