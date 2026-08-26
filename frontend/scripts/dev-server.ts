import index from "../index.html";

const backendOrigin = "http://127.0.0.1:18080";

const server = Bun.serve({
  hostname: "127.0.0.1",
  port: 5173,
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
