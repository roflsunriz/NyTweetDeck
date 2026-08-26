import { rm } from "node:fs/promises";
import { resolve } from "node:path";

const outputDirectory = resolve(import.meta.dir, "../../target/classes/static");
await rm(outputDirectory, { force: true, recursive: true });

const result = await Bun.build({
  entrypoints: [resolve(import.meta.dir, "../index.html")],
  outdir: outputDirectory,
  target: "browser",
  minify: true,
  sourcemap: "linked",
  naming: {
    entry: "[name].[ext]",
    chunk: "[name]-[hash].[ext]",
    asset: "[name]-[hash].[ext]",
  },
});

if (!result.success) {
  for (const log of result.logs) {
    console.error(log);
  }
  process.exit(1);
}

console.info(`Frontend built to ${outputDirectory}`);
