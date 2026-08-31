import { readFile, writeFile } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { coverageCatalogCounts, NYTD_PRODUCT_INVARIANTS } from "./x-reference-coverage-catalog";

interface LatestCapture {
  captureDirectory: string;
  schemaVersion: number;
}

interface ManifestFile {
  bytes: number;
  mimeType: string;
  url: string;
}

interface ElementInventory {
  interactiveStates: Record<string, number>;
  roles: Record<string, number>;
  safeTestIds: Record<string, number>;
  tags: Record<string, number>;
}

interface CaptureManifest {
  files: ManifestFile[];
  observation?: { elementInventory?: ElementInventory };
  schemaVersion: number;
}

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const CAPTURE_ROOT = resolve(
  SCRIPT_DIRECTORY,
  "..",
  "..",
  "src",
  "sandbox",
  "x-reference-captures",
);
const STRUCTURAL_TOKENS = new Set([
  "assets",
  "bundle",
  "client",
  "js",
  "loader",
  "loaders",
  "ondemand",
  "responsive",
  "shared",
  "web",
]);

async function readJson<T>(path: string): Promise<T> {
  return JSON.parse(await readFile(path, "utf8")) as T;
}

function bundleTokens(url: string): string[] {
  const name = basename(new URL(url).pathname)
    .replace(/\.[0-9a-f]{16}[a-z]?\.(?:m?js|css)$/i, "")
    .replace(/\.(?:m?js|css)$/i, "");
  return name
    .split(/[~._-]+/)
    .map((token) => token.trim())
    .filter(
      (token) =>
        token.length >= 3 &&
        token.length <= 80 &&
        /^[a-zA-Z][a-zA-Z0-9]+$/.test(token) &&
        !STRUCTURAL_TOKENS.has(token.toLowerCase()),
    );
}

async function main(): Promise<void> {
  const latest = await readJson<LatestCapture>(join(CAPTURE_ROOT, "latest.json"));
  if (latest.schemaVersion !== 1 || latest.captureDirectory.includes("..")) {
    throw new Error("latest captureの形式が不正です。");
  }
  const captureDirectory = resolve(CAPTURE_ROOT, latest.captureDirectory);
  if (!captureDirectory.startsWith(CAPTURE_ROOT)) {
    throw new Error("latest captureがsandbox外を参照しています。");
  }
  const manifest = await readJson<CaptureManifest>(join(captureDirectory, "manifest.json"));
  const tokenCounts = new Map<string, number>();
  for (const file of manifest.files) {
    for (const token of bundleTokens(file.url)) {
      tokenCounts.set(token, (tokenCounts.get(token) ?? 0) + 1);
    }
  }
  const discoveredBundleTokens = [...tokenCounts.entries()]
    .sort(
      ([leftName, leftCount], [rightName, rightCount]) =>
        rightCount - leftCount || leftName.localeCompare(rightName),
    )
    .map(([name, count]) => ({ count, name }));
  const inventory = {
    captureDirectory: latest.captureDirectory,
    catalogCounts: coverageCatalogCounts(),
    discoveredBundleTokens,
    elementInventory: manifest.observation?.elementInventory ?? null,
    inventoryGeneratedAt: new Date().toISOString(),
    nytweetdeckProductInvariants: NYTD_PRODUCT_INVARIANTS,
    resourceCounts: {
      css: manifest.files.filter((file) => new URL(file.url).pathname.endsWith(".css")).length,
      javascript: manifest.files.filter((file) => /\.(?:m?js)$/i.test(new URL(file.url).pathname))
        .length,
      total: manifest.files.length,
      totalBytes: manifest.files.reduce((sum, file) => sum + file.bytes, 0),
    },
    schemaVersion: 1,
  };
  await writeFile(
    join(captureDirectory, "inventory.json"),
    `${JSON.stringify(inventory, null, 2)}\n`,
    "utf8",
  );
  console.log(
    JSON.stringify(
      {
        catalogCounts: inventory.catalogCounts,
        discoveredBundleTokenCount: discoveredBundleTokens.length,
        elementInventoryAvailable: inventory.elementInventory !== null,
        resourceCounts: inventory.resourceCounts,
      },
      null,
      2,
    ),
  );
}

await main();
