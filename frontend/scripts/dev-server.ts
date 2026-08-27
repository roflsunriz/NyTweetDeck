import index from "../index.html";

const backendOrigin = readBackendOrigin();
const frontendPort = readPort(process.env.NYTWEETDECK_FRONTEND_PORT, 5173);

const server = Bun.serve({
  hostname: "127.0.0.1",
  port: frontendPort,
  development: true,
  routes: {
    "/": index,
  },
  async fetch(request) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith("/api/")) {
      return new Response("Not Found", { status: 404 });
    }

    const backendUrl = new URL(url.pathname + url.search, backendOrigin);
    const headers = new Headers(request.headers);
    headers.delete("host");
    return fetch(backendUrl, {
      method: request.method,
      headers,
      body: request.body,
      redirect: "manual",
    });
  },
});

console.info(`NyTweetDeck frontend: ${server.url}`);

function readBackendOrigin(): string {
  const value = process.env.NYTWEETDECK_BACKEND_ORIGIN ?? "http://127.0.0.1:18080";
  const url = new URL(value);
  if (
    (url.protocol !== "http:" && url.protocol !== "https:") ||
    !["127.0.0.1", "localhost", "[::1]"].includes(url.hostname) ||
    url.username.length > 0 ||
    url.password.length > 0 ||
    url.pathname !== "/" ||
    url.search.length > 0 ||
    url.hash.length > 0
  ) {
    throw new Error(`NYTWEETDECK_BACKEND_ORIGIN must be a loopback HTTP origin: ${value}`);
  }
  return url.origin;
}

function readPort(value: string | undefined, fallback: number): number {
  if (value === undefined) return fallback;
  if (!/^\d+$/u.test(value)) {
    throw new Error(`NYTWEETDECK_FRONTEND_PORT must be an integer: ${value}`);
  }
  const port = Number(value);
  if (!Number.isSafeInteger(port) || port < 1024 || port > 65535) {
    throw new Error(`NYTWEETDECK_FRONTEND_PORT must be between 1024 and 65535: ${value}`);
  }
  return port;
}
