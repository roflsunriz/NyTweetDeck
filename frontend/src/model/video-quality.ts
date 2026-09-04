import type { VideoQuality } from "./layout";
import type { VideoVariant } from "./timeline";

export function selectVideoSource(
  fallbackSource: string,
  variants: readonly VideoVariant[] | undefined,
  quality: VideoQuality,
): string {
  return selectVideoSources(fallbackSource, variants, quality)[0] ?? fallbackSource;
}

export function selectVideoSources(
  fallbackSource: string,
  variants: readonly VideoVariant[] | undefined,
  quality: VideoQuality,
): string[] {
  const candidates = (variants ?? [])
    .filter((variant) => variant.url.trim().length > 0)
    .slice()
    .sort((left, right) => (left.bitrate ?? 0) - (right.bitrate ?? 0));
  if (candidates.length === 0) return fallbackSource.trim().length > 0 ? [fallbackSource] : [];

  const index =
    quality === "low"
      ? 0
      : quality === "medium"
        ? Math.floor((candidates.length - 1) / 2)
        : candidates.length - 1;
  const preferred = candidates[index]?.url ?? fallbackSource;
  if (quality !== "auto") return [preferred];
  return [
    ...new Set(
      candidates
        .slice()
        .reverse()
        .map((candidate) => candidate.url),
    ),
  ];
}
