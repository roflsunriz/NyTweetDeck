import { fetchWithTimeout } from "./fetch-with-timeout";

export interface DesktopRelease {
  tagName: string;
  assetName: string;
  downloadUrl: string;
  sizeBytes: number | null;
  currentVersion: string;
  updateAvailable: boolean;
}

export async function loadLatestDesktopRelease(): Promise<DesktopRelease> {
  const response = await fetchWithTimeout("/api/v1/updates/desktop/latest");
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return parseDesktopRelease(await response.json());
}

export function parseDesktopRelease(value: unknown): DesktopRelease {
  if (typeof value !== "object" || value === null) throw new Error("invalid release");
  const candidate = value as Record<string, unknown>;
  const tagName = typeof candidate.tagName === "string" ? candidate.tagName : "";
  const version = /^v([0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?)$/.exec(tagName)?.[1];
  if (version === undefined) throw new Error("invalid release tag");
  const assetName = `NyTweetDeck-v${version}.zip`;
  if (candidate.assetName !== assetName) throw new Error("invalid release asset");
  if (typeof candidate.downloadUrl !== "string") throw new Error("invalid release URL");
  const downloadUrl = new URL(candidate.downloadUrl);
  const expectedPath = `/roflsunriz/NyTweetDeck/releases/download/${tagName}/${assetName}`;
  if (
    downloadUrl.protocol !== "https:" ||
    downloadUrl.hostname !== "github.com" ||
    downloadUrl.port !== "" ||
    downloadUrl.username !== "" ||
    downloadUrl.password !== "" ||
    downloadUrl.pathname !== expectedPath ||
    downloadUrl.search !== "" ||
    downloadUrl.hash !== ""
  ) {
    throw new Error("invalid release URL");
  }
  const sizeBytes = candidate.sizeBytes;
  if (
    typeof candidate.currentVersion !== "string" ||
    typeof candidate.updateAvailable !== "boolean"
  ) {
    throw new Error("invalid update status");
  }
  if (sizeBytes !== null && (!Number.isSafeInteger(sizeBytes) || Number(sizeBytes) < 0)) {
    throw new Error("invalid release size");
  }
  return {
    tagName,
    assetName,
    downloadUrl: downloadUrl.href,
    sizeBytes: sizeBytes as number | null,
    currentVersion: candidate.currentVersion,
    updateAvailable: candidate.updateAvailable,
  };
}

export function downloadDesktopRelease(release: DesktopRelease): void {
  const anchor = document.createElement("a");
  anchor.href = release.downloadUrl;
  anchor.download = release.assetName;
  anchor.rel = "noreferrer";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
}
