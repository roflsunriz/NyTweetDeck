import { type AppLayout, isAppLayout } from "./layout";

export const layoutTransferFormat = "NyTweetDeckSettings";
export const layoutTransferVersion = 1 as const;

interface LayoutTransferDocument {
  format: typeof layoutTransferFormat;
  version: typeof layoutTransferVersion;
  exportedAt: string;
  layout: AppLayout;
}

export function exportLayoutSettings(layout: AppLayout, now = new Date()): string {
  const document: LayoutTransferDocument = {
    format: layoutTransferFormat,
    version: layoutTransferVersion,
    exportedAt: now.toISOString(),
    layout: { ...layout, activeAccountId: null },
  };
  return `${JSON.stringify(document, null, 2)}\n`;
}

export function importLayoutSettings(serialized: string, current: AppLayout): AppLayout {
  let value: unknown;
  try {
    value = JSON.parse(serialized);
  } catch {
    throw new Error("設定ファイルは有効なJSONではありません。");
  }
  if (!isRecord(value) || value.format !== layoutTransferFormat) {
    throw new Error("NyTweetDeck設定ファイルではありません。");
  }
  if (value.version !== layoutTransferVersion) {
    throw new Error("対応していない設定ファイルのバージョンです。");
  }
  if (typeof value.exportedAt !== "string" || !Number.isFinite(Date.parse(value.exportedAt))) {
    throw new Error("設定ファイルの出力日時が不正です。");
  }
  if (!isAppLayout(value.layout)) {
    throw new Error("設定ファイルのレイアウトが不正です。");
  }
  return { ...value.layout, activeAccountId: current.activeAccountId };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
