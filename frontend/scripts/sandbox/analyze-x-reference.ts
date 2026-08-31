import { readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

interface LatestCapture {
  captureDirectory: string;
  schemaVersion: number;
}

interface ManifestFile {
  bytes: number;
  fileName: string;
  mimeType: string;
  sha256: string;
  status: number;
  url: string;
}

interface CaptureManifest {
  files: ManifestFile[];
  schemaVersion: number;
}

interface FileSignalSummary {
  bytes: number;
  fileName: string;
  signals: Record<string, number>;
  total: number;
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

const SIGNALS = {
  accountNavigation: ["User-Name", "UserProfile", "screen_name", "user_results"],
  imageViewer: ["ImageViewer", "mediaViewer", "tweetPhoto", "Gallery"],
  replyTree: ["TweetDetail", "conversation", "ConversationTimeline", "reply"],
  urlNormalization: ["unwound_url", "expanded_url", "display_url", "t.co"],
  videoPlayer: ["VideoPlayer", "video_player", "playbackRate", "media_player"],
} as const;

function countOccurrences(source: string, needle: string): number {
  let count = 0;
  let offset = 0;
  while (true) {
    const index = source.indexOf(needle, offset);
    if (index < 0) return count;
    count += 1;
    offset = index + needle.length;
  }
}

async function readJson<T>(path: string): Promise<T> {
  return JSON.parse(await readFile(path, "utf8")) as T;
}

async function deminifyInMemory(source: string, fileName: string): Promise<boolean> {
  const process = Bun.spawn(["bun", "x", "biome", "format", "--stdin-file-path", fileName], {
    cwd: resolve(SCRIPT_DIRECTORY, "..", ".."),
    stderr: "pipe",
    stdin: new Blob([source]),
    stdout: "pipe",
  });
  await process.exited;
  if (process.exitCode !== 0) return false;
  await new Response(process.stdout).text();
  return true;
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
  const fileSignals: FileSignalSummary[] = [];
  for (const file of manifest.files) {
    if (!/\.(?:m?js)$/i.test(new URL(file.url).pathname)) continue;
    const source = await readFile(join(captureDirectory, file.fileName), "utf8");
    const signals = Object.fromEntries(
      Object.entries(SIGNALS).map(([name, needles]) => [
        name,
        needles.reduce((sum, needle) => sum + countOccurrences(source, needle), 0),
      ]),
    );
    const total = Object.values(signals).reduce((sum, count) => sum + count, 0);
    fileSignals.push({ bytes: file.bytes, fileName: file.fileName, signals, total });
  }
  fileSignals.sort((left, right) => right.total - left.total || right.bytes - left.bytes);
  const selected = fileSignals.filter((file) => file.total > 0).slice(0, 8);
  const deminifiedInMemory = [];
  for (const file of selected) {
    const source = await readFile(join(captureDirectory, file.fileName), "utf8");
    deminifiedInMemory.push({
      fileName: file.fileName,
      succeeded: await deminifyInMemory(source, file.fileName),
    });
  }
  const totals = Object.fromEntries(
    Object.keys(SIGNALS).map((name) => [
      name,
      fileSignals.reduce((sum, file) => sum + (file.signals[name] ?? 0), 0),
    ]),
  );
  const analysis = {
    analyzedAt: new Date().toISOString(),
    captureDirectory: latest.captureDirectory,
    deminifiedInMemory,
    rawOfficialCodeCopiedIntoProduct: false,
    schemaVersion: 1,
    topFiles: selected,
    totals,
  };
  await writeFile(
    join(captureDirectory, "analysis.json"),
    `${JSON.stringify(analysis, null, 2)}\n`,
    "utf8",
  );
  console.log(JSON.stringify({ deminifiedInMemory, totals }, null, 2));
}

await main();
