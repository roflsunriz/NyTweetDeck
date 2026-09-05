import { CdpClient, waitForPageCondition } from "./cdp-client";
const output = await Bun.build({
  entrypoints: [
    new URL("./translation-memory-cdp-fixture.tsx", import.meta.url).pathname.replace(
      /^\/(\w:)/,
      "$1",
    ),
  ],
  target: "browser",
});
if (!output.success) throw new Error("Fixture build failed");
const bundle = await output.outputs[0]?.text();
let translations = 0;
const server = Bun.serve({
  hostname: "127.0.0.1",
  port: 0,
  fetch(request) {
    const path = new URL(request.url).pathname;
    if (path === "/fixture.js")
      return new Response(bundle, { headers: { "Content-Type": "text/javascript" } });
    if (path.endsWith("/translation")) {
      translations++;
      return Response.json(
        path.includes("community-notes")
          ? { noteId: "456", targetLanguage: "ja", available: true, text: "ノート訳", sources: [] }
          : {
              postId: "123",
              sourceLanguage: "en",
              targetLanguage: "ja",
              text: "ポスト訳",
              provider: "X",
            },
      );
    }
    return new Response(
      '<!doctype html><meta charset="utf-8"><div id="root"></div><script src="/fixture.js"></script>',
      { headers: { "Content-Type": "text/html" } },
    );
  },
});
const port = process.env.CHROME_CDP_PORT ?? "9233";
const target = (await fetch(
  `http://127.0.0.1:${port}/json/new?${encodeURIComponent(`http://127.0.0.1:${server.port}`)}`,
  { method: "PUT" },
).then((response) => response.json())) as { id: string; webSocketDebuggerUrl: string };
const client = await CdpClient.connect(target.webSocketDebuggerUrl);
try {
  await waitForPageCondition(
    client,
    'document.querySelector("[data-note]")?.textContent === "ノート訳" && document.querySelector("[data-post]")?.textContent === "ポスト訳"',
  );
  if (translations !== 2) throw new Error(`Initial translation calls: ${translations}`);
  await client.evaluate('document.querySelector("[data-toggle]").click()');
  await waitForPageCondition(client, 'document.querySelector("[data-note]") === null');
  await client.evaluate('document.querySelector("[data-toggle]").click()');
  await waitForPageCondition(
    client,
    'document.querySelector("[data-note]")?.textContent === "ノート訳"',
  );
  const loading = await client.evaluate('document.querySelectorAll("[data-loading=true]").length');
  if (translations !== 2 || loading !== 0)
    throw new Error("Reopening did not reuse translation memory");
  console.info(
    JSON.stringify({
      initialRequests: 2,
      reopenRequests: translations - 2,
      loading,
      postAndNote: true,
    }),
  );
} finally {
  client.close();
  await fetch(`http://127.0.0.1:${port}/json/close/${target.id}`);
  server.stop(true);
}
