export async function fetchWithTimeout(
  input: RequestInfo | URL,
  init: RequestInit = {},
  timeoutMilliseconds = 15_000,
): Promise<Response> {
  const controller = new AbortController();
  const upstreamSignal = init.signal;
  const abortFromUpstream = () => controller.abort(upstreamSignal?.reason);
  if (upstreamSignal?.aborted === true) {
    abortFromUpstream();
  } else {
    upstreamSignal?.addEventListener("abort", abortFromUpstream, { once: true });
  }
  const timeout = globalThis.setTimeout(
    () => controller.abort(new DOMException("Request timed out", "TimeoutError")),
    Math.max(1, timeoutMilliseconds),
  );
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } finally {
    globalThis.clearTimeout(timeout);
    upstreamSignal?.removeEventListener("abort", abortFromUpstream);
  }
}
