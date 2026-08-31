import { readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import {
  type ReadOnlySurfaceDefinition,
  READ_ONLY_X_SURFACES,
} from "./x-reference-surface-catalog";

interface LatestCapture {
  captureDirectory: string;
  schemaVersion: number;
}

interface StructureObservation {
  accountNavigationCandidateCount: number;
  actionButtonCount: number;
  articleCount: number;
  expectedRouteReached: boolean;
  imageCount: number;
  loggedIn: boolean;
  routeShape: string;
  videoCount: number;
}

interface CaptureManifest {
  files: Array<{ bytes: number }>;
  observation: StructureObservation;
  schemaVersion: number;
  surfaceId: string;
}

interface SurfaceResult {
  captureDirectory?: string;
  error?: string;
  fileCount?: number;
  observation?: StructureObservation;
  surfaceId: string;
  totalBytes?: number;
}

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = resolve(SCRIPT_DIRECTORY, "..", "..");
const CAPTURE_ROOT = resolve(FRONTEND_ROOT, "src", "sandbox", "x-reference-captures");

function parseArgument(name: string): string | undefined {
  const prefix = `--${name}=`;
  return process.argv
    .slice(2)
    .find((argument) => argument.startsWith(prefix))
    ?.slice(prefix.length);
}

async function readJson<T>(path: string): Promise<T> {
  return JSON.parse(await readFile(path, "utf8")) as T;
}

function selectedSurfaces(): readonly ReadOnlySurfaceDefinition[] {
  const requested = parseArgument("surfaces");
  if (requested === undefined || requested.trim() === "") return READ_ONLY_X_SURFACES;
  const requestedIds = new Set(
    requested
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean),
  );
  const knownIds = new Set(READ_ONLY_X_SURFACES.map(({ id }) => id));
  const unknown = [...requestedIds].filter((id) => !knownIds.has(id));
  if (unknown.length > 0) {
    throw new Error(`未知のsurface IDです: ${unknown.join(", ")}`);
  }
  return READ_ONLY_X_SURFACES.filter(({ id }) => requestedIds.has(id));
}

async function captureSurface(surfaceId: string, path: string): Promise<SurfaceResult> {
  const process = Bun.spawn(
    [
      "bun",
      "scripts/sandbox/capture-x-reference.ts",
      `--surface-id=${surfaceId}`,
      `--url=https://x.com${path}`,
      "--settle-ms=5000",
    ],
    { cwd: FRONTEND_ROOT, stderr: "pipe", stdout: "pipe" },
  );
  const [exitCode, stderr] = await Promise.all([
    process.exited,
    new Response(process.stderr).text(),
  ]);
  await new Response(process.stdout).text();
  if (exitCode !== 0) {
    return {
      error: stderr.trim().split(/\r?\n/).at(-1)?.slice(0, 300) || `exit ${exitCode}`,
      surfaceId,
    };
  }
  const latest = await readJson<LatestCapture>(join(CAPTURE_ROOT, "latest.json"));
  if (latest.schemaVersion !== 1 || latest.captureDirectory.includes("..")) {
    return { error: "latest capture形式不正", surfaceId };
  }
  const captureDirectory = resolve(CAPTURE_ROOT, latest.captureDirectory);
  if (!captureDirectory.startsWith(CAPTURE_ROOT)) {
    return { error: "captureがsandbox外を参照", surfaceId };
  }
  const manifest = await readJson<CaptureManifest>(join(captureDirectory, "manifest.json"));
  if (manifest.schemaVersion !== 1 || manifest.surfaceId !== surfaceId) {
    return { error: "surface manifest不一致", surfaceId };
  }
  return {
    captureDirectory: latest.captureDirectory,
    fileCount: manifest.files.length,
    observation: manifest.observation,
    surfaceId,
    totalBytes: manifest.files.reduce((sum, file) => sum + file.bytes, 0),
  };
}

function timestampForPath(date: Date): string {
  return date.toISOString().replace(/[:.]/g, "-");
}

async function main(): Promise<void> {
  const startedAt = new Date();
  const results: SurfaceResult[] = [];
  for (const surface of selectedSurfaces()) {
    const result = await captureSurface(surface.id, surface.path);
    results.push(result);
    console.log(
      result.error === undefined
        ? `${surface.id}: files=${result.fileCount} posts=${result.observation?.articleCount} route=${result.observation?.expectedRouteReached}`
        : `${surface.id}: failed=${result.error}`,
    );
  }
  const report = {
    completedAt: new Date().toISOString(),
    failedCount: results.filter(({ error }) => error !== undefined).length,
    mutationPerformed: false,
    results,
    schemaVersion: 1,
    startedAt: startedAt.toISOString(),
    surfaceCount: results.length,
    succeededCount: results.filter(({ error }) => error === undefined).length,
  };
  const reportName = `surface-observation-${timestampForPath(startedAt)}.json`;
  await writeFile(join(CAPTURE_ROOT, reportName), `${JSON.stringify(report, null, 2)}\n`, "utf8");
  await writeFile(
    join(CAPTURE_ROOT, "surface-observation-latest.json"),
    `${JSON.stringify({ report: reportName, schemaVersion: 1 }, null, 2)}\n`,
    "utf8",
  );
  console.log(
    `surface巡回完了: ${report.succeededCount}/${report.surfaceCount}成功、${report.failedCount}失敗`,
  );
  if (report.failedCount > 0) process.exitCode = 1;
}

await main();
