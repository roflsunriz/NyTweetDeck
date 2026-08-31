import { readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

interface LatestDynamicCapture {
  captureDirectory: string;
  schemaVersion: number;
}

interface DynamicManifest {
  results: Array<{
    actionPerformed: boolean;
    after: {
      dialogCount: number;
      expandedCount: number;
      menuCount: number;
      modalCount: number;
      routeShape: string;
      selectedTabCount: number;
    };
    before: {
      dialogCount: number;
      expandedCount: number;
      menuCount: number;
      modalCount: number;
      routeShape: string;
      selectedTabCount: number;
    };
    id: string;
    intermediate?: {
      expandedCount: number;
      menuCount: number;
    };
  }>;
  schemaVersion: number;
  viewport: { height: number; width: number };
}

interface MatrixObservation {
  captureDirectory: string;
  id: string;
  results: Array<{
    actionPerformed: boolean;
    dialogDelta: number;
    expandedDelta: number;
    id: string;
    intermediateExpandedDelta: number | null;
    intermediateMenuDelta: number | null;
    menuDelta: number;
    modalDelta: number;
    routeAfter: string;
    routeChanged: boolean;
    selectedTabCount: number;
  }>;
  viewport: { height: number; width: number };
}

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = resolve(SCRIPT_DIRECTORY, "..", "..");
const DYNAMIC_ROOT = resolve(
  FRONTEND_ROOT,
  "src",
  "sandbox",
  "x-reference-captures",
  "dynamic-scenarios",
);
const VIEWPORTS = [
  { height: 900, id: "desktop", width: 1440 },
  { height: 1024, id: "tablet", width: 768 },
  { height: 844, id: "phone", width: 390 },
] as const;

async function readJson<T>(path: string): Promise<T> {
  return JSON.parse(await readFile(path, "utf8")) as T;
}

function timestampForPath(date: Date): string {
  return date.toISOString().replace(/[:.]/g, "-");
}

async function main(): Promise<void> {
  const startedAt = new Date();
  const observations: MatrixObservation[] = [];
  for (const viewport of VIEWPORTS) {
    const process = Bun.spawn(
      [
        "bun",
        "scripts/sandbox/observe-x-dynamic-scenarios.ts",
        `--viewport=${viewport.width}x${viewport.height}`,
      ],
      { cwd: FRONTEND_ROOT, stderr: "pipe", stdout: "pipe" },
    );
    const [exitCode, stderr, stdout] = await Promise.all([
      process.exited,
      new Response(process.stderr).text(),
      new Response(process.stdout).text(),
    ]);
    if (exitCode !== 0) {
      throw new Error(
        `${viewport.id}観測失敗: ${stderr.trim().split(/\r?\n/).at(-1)?.slice(0, 300) ?? exitCode}`,
      );
    }
    const latest = await readJson<LatestDynamicCapture>(join(DYNAMIC_ROOT, "latest.json"));
    if (latest.schemaVersion !== 1 || latest.captureDirectory.includes("..")) {
      throw new Error(`${viewport.id}のlatest capture形式が不正です。`);
    }
    const manifest = await readJson<DynamicManifest>(
      join(DYNAMIC_ROOT, latest.captureDirectory, "manifest.json"),
    );
    if (
      manifest.schemaVersion !== 1 ||
      manifest.viewport.width !== viewport.width ||
      manifest.viewport.height !== viewport.height
    ) {
      throw new Error(`${viewport.id}のmanifest viewportが不一致です。`);
    }
    observations.push({
      captureDirectory: latest.captureDirectory,
      id: viewport.id,
      results: manifest.results.map(({ actionPerformed, after, before, id, intermediate }) => ({
        actionPerformed,
        dialogDelta: after.dialogCount - before.dialogCount,
        expandedDelta: after.expandedCount - before.expandedCount,
        id,
        intermediateExpandedDelta:
          intermediate === undefined ? null : intermediate.expandedCount - before.expandedCount,
        intermediateMenuDelta:
          intermediate === undefined ? null : intermediate.menuCount - before.menuCount,
        menuDelta: after.menuCount - before.menuCount,
        modalDelta: after.modalCount - before.modalCount,
        routeAfter: after.routeShape,
        routeChanged: after.routeShape !== before.routeShape,
        selectedTabCount: after.selectedTabCount,
      })),
      viewport: { height: viewport.height, width: viewport.width },
    });
    console.log(
      `${viewport.id}: ${manifest.results.filter(({ actionPerformed }) => actionPerformed).length}/${manifest.results.length}操作`,
    );
    if (!stdout.includes("動的scenario観測完了")) {
      throw new Error(`${viewport.id}の完了出力がありません。`);
    }
  }
  const report = {
    completedAt: new Date().toISOString(),
    mutationPerformed: false,
    observations,
    schemaVersion: 1,
    startedAt: startedAt.toISOString(),
  };
  const reportName = `dynamic-matrix-${timestampForPath(startedAt)}.json`;
  await writeFile(join(DYNAMIC_ROOT, reportName), `${JSON.stringify(report, null, 2)}\n`, "utf8");
  await writeFile(
    join(DYNAMIC_ROOT, "matrix-latest.json"),
    `${JSON.stringify({ report: reportName, schemaVersion: 1 }, null, 2)}\n`,
    "utf8",
  );
  console.log(`動的viewport matrix完了: ${observations.length}/${VIEWPORTS.length}`);
}

await main();
