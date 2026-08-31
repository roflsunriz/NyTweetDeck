import { readFile, writeFile } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { coverageCatalogCounts } from "./x-reference-coverage-catalog";
import { READ_ONLY_X_SURFACES } from "./x-reference-surface-catalog";

interface SurfaceObservationPointer {
  report: string;
  schemaVersion: number;
}

interface SurfaceResult {
  captureDirectory?: string;
  error?: string;
  surfaceId: string;
}

interface SurfaceObservationReport {
  results: SurfaceResult[];
  schemaVersion: number;
}

interface ElementInventory {
  interactiveStates: Record<string, number>;
  roles: Record<string, number>;
  safeTestIds: Record<string, number>;
  tags: Record<string, number>;
}

interface CaptureManifest {
  files: Array<{ bytes: number; url: string }>;
  observation: {
    articleCount: number;
    elementInventory: ElementInventory;
    expectedRouteReached: boolean;
    loggedIn: boolean;
    routeShape: string;
  };
  schemaVersion: number;
  surfaceId: string;
}

type AggregatedSurface =
  | { error: string; surfaceId: string }
  | {
      articleCount: number;
      expectedRouteReached: boolean;
      fileCount: number;
      loggedIn: boolean;
      routeShape: string;
      surfaceId: string;
      totalBytes: number;
    };

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
  return basename(new URL(url).pathname)
    .replace(/\.[0-9a-f]{16}[a-z]?\.(?:m?js|css)$/i, "")
    .replace(/\.(?:m?js|css)$/i, "")
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

function mergeCounts(target: Map<string, number>, source: Record<string, number>): void {
  for (const [name, count] of Object.entries(source)) {
    target.set(name, (target.get(name) ?? 0) + count);
  }
}

function sortedCounts(values: Map<string, number>): Array<{ count: number; name: string }> {
  return [...values.entries()]
    .sort(
      ([leftName, leftCount], [rightName, rightCount]) =>
        rightCount - leftCount || leftName.localeCompare(rightName),
    )
    .map(([name, count]) => ({ count, name }));
}

async function main(): Promise<void> {
  const pointer = await readJson<SurfaceObservationPointer>(
    join(CAPTURE_ROOT, "surface-observation-latest.json"),
  );
  if (pointer.schemaVersion !== 1 || pointer.report.includes("..")) {
    throw new Error("surface observation pointerの形式が不正です。");
  }
  const report = await readJson<SurfaceObservationReport>(join(CAPTURE_ROOT, pointer.report));
  const bundleTokenCounts = new Map<string, number>();
  const roleCounts = new Map<string, number>();
  const safeTestIdCounts = new Map<string, number>();
  const tagCounts = new Map<string, number>();
  const stateCounts = new Map<string, number>();
  const surfaces: AggregatedSurface[] = [];
  for (const result of report.results) {
    if (result.error !== undefined || result.captureDirectory === undefined) {
      surfaces.push({ error: result.error ?? "captureなし", surfaceId: result.surfaceId });
      continue;
    }
    const captureDirectory = resolve(CAPTURE_ROOT, result.captureDirectory);
    if (!captureDirectory.startsWith(CAPTURE_ROOT)) {
      surfaces.push({ error: "sandbox外capture", surfaceId: result.surfaceId });
      continue;
    }
    const manifest = await readJson<CaptureManifest>(join(captureDirectory, "manifest.json"));
    if (manifest.schemaVersion !== 1 || manifest.surfaceId !== result.surfaceId) {
      surfaces.push({ error: "manifest不一致", surfaceId: result.surfaceId });
      continue;
    }
    for (const file of manifest.files) {
      for (const token of bundleTokens(file.url)) {
        bundleTokenCounts.set(token, (bundleTokenCounts.get(token) ?? 0) + 1);
      }
    }
    mergeCounts(roleCounts, manifest.observation.elementInventory.roles);
    mergeCounts(safeTestIdCounts, manifest.observation.elementInventory.safeTestIds);
    mergeCounts(tagCounts, manifest.observation.elementInventory.tags);
    mergeCounts(stateCounts, manifest.observation.elementInventory.interactiveStates);
    surfaces.push({
      articleCount: manifest.observation.articleCount,
      expectedRouteReached: manifest.observation.expectedRouteReached,
      fileCount: manifest.files.length,
      loggedIn: manifest.observation.loggedIn,
      routeShape: manifest.observation.routeShape,
      surfaceId: result.surfaceId,
      totalBytes: manifest.files.reduce((sum, file) => sum + file.bytes, 0),
    });
  }
  const observedIds = new Set(
    surfaces.filter((surface) => !("error" in surface)).map(({ surfaceId }) => surfaceId),
  );
  const inventory = {
    aggregateElementInventory: {
      interactiveStates: sortedCounts(stateCounts),
      roles: sortedCounts(roleCounts),
      safeTestIds: sortedCounts(safeTestIdCounts),
      tags: sortedCounts(tagCounts),
    },
    catalogCounts: coverageCatalogCounts(),
    discoveredBundleTokens: sortedCounts(bundleTokenCounts),
    generatedAt: new Date().toISOString(),
    missingReadOnlySurfaceIds: READ_ONLY_X_SURFACES.map(({ id }) => id).filter(
      (id) => !observedIds.has(id),
    ),
    schemaVersion: 1,
    sourceReport: pointer.report,
    surfaces,
  };
  await writeFile(
    join(CAPTURE_ROOT, "surface-inventory-latest.json"),
    `${JSON.stringify(inventory, null, 2)}\n`,
    "utf8",
  );
  console.log(
    JSON.stringify(
      {
        bundleTokenCount: inventory.discoveredBundleTokens.length,
        exactRouteCount: surfaces.filter(
          (surface) => !("error" in surface) && surface.expectedRouteReached,
        ).length,
        failedSurfaceCount: surfaces.filter((surface) => "error" in surface).length,
        missingReadOnlySurfaceIds: inventory.missingReadOnlySurfaceIds,
        observedSurfaceCount: observedIds.size,
        roleCount: inventory.aggregateElementInventory.roles.length,
        safeTestIdCount: inventory.aggregateElementInventory.safeTestIds.length,
      },
      null,
      2,
    ),
  );
}

await main();
