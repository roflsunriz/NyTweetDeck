import type { VideoQuality } from "./layout";
import type { VideoVariant } from "./timeline";

export function selectVideoSource(
  fallbackSource: string,
  variants: readonly VideoVariant[] | undefined,
  quality: VideoQuality,
): string {
  const candidates = (variants ?? [])
    .filter((variant) => variant.url.trim().length > 0)
    .slice()
    .sort((left, right) => (left.bitrate ?? 0) - (right.bitrate ?? 0));
  if (candidates.length === 0) return fallbackSource;

  const index =
    quality === "low"
      ? 0
      : quality === "medium"
        ? Math.floor((candidates.length - 1) / 2)
        : candidates.length - 1;
  return candidates[index]?.url ?? fallbackSource;
}
