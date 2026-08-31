import { createHash } from "node:crypto";
import { basename } from "node:path";

const X_ASSET_HOST = "abs.twimg.com";
const X_ASSET_PREFIXES = ["/responsive-web/client-web/", "/x-web/x-web/"] as const;

export function sanitizeOfficialAssetUrl(value: string): string | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (
    url.protocol !== "https:" ||
    url.hostname !== X_ASSET_HOST ||
    !X_ASSET_PREFIXES.some((prefix) => url.pathname.startsWith(prefix)) ||
    !/\.(?:m?js|css)$/i.test(url.pathname)
  ) {
    return null;
  }
  url.username = "";
  url.password = "";
  url.search = "";
  url.hash = "";
  return url.href;
}

export function makeCapturedAssetFileName(url: string): string {
  const parsed = new URL(url);
  const sourceName = basename(parsed.pathname).replace(/[^a-zA-Z0-9_.-]/g, "_");
  const urlHash = createHash("sha256").update(url).digest("hex").slice(0, 12);
  return `${urlHash}-${sourceName || "bundle.js"}`;
}

export function sha256(bytes: Uint8Array): string {
  return createHash("sha256").update(bytes).digest("hex");
}
