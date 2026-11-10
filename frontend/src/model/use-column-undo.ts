import { useEffect, useRef, useState } from "react";
import type { AppLayout, ColumnConfig } from "./layout";

export const columnUndoMilliseconds = 10_000;
export interface RemovedColumn {
  column: ColumnConfig;
  index: number;
  preceding: string[];
  following: string[];
  expiresAt: number;
}

export function restoreColumn(columns: ColumnConfig[], removed: RemovedColumn): ColumnConfig[] {
  if (columns.some((column) => column.id === removed.column.id)) return columns;
  const preceding = removed.preceding.find((id) => columns.some((column) => column.id === id));
  const following = removed.following.find((id) => columns.some((column) => column.id === id));
  const index =
    following !== undefined
      ? columns.findIndex((column) => column.id === following)
      : preceding === undefined
        ? Math.min(removed.index, columns.length)
        : columns.findIndex((column) => column.id === preceding) + 1;
  return [...columns.slice(0, index), removed.column, ...columns.slice(index)];
}

export function useColumnUndo(
  layout: AppLayout | null,
  setLayout: (update: (current: AppLayout) => AppLayout) => void,
) {
  const [removed, setRemoved] = useState<RemovedColumn[]>([]);
  const currentRemoved = useRef(removed);
  currentRemoved.current = removed;
  useEffect(() => {
    if (removed.length === 0) return;
    const timer = globalThis.setTimeout(
      () => {
        setRemoved((current) => current.filter((item) => item.expiresAt > Date.now()));
      },
      Math.max(0, Math.min(...removed.map((item) => item.expiresAt)) - Date.now()),
    );
    return () => globalThis.clearTimeout(timer);
  }, [removed]);

  const remove = (id: string) => {
    if (layout === null) return;
    const logicalColumns = [...currentRemoved.current]
      .reverse()
      .reduce((columns, item) => restoreColumn(columns, item), layout.columns);
    const index = logicalColumns.findIndex((column) => column.id === id);
    const column = layout.columns.find((item) => item.id === id);
    if (column === undefined) return;
    setRemoved((current) => [
      ...current.filter((item) => item.column.id !== id),
      {
        column,
        index,
        preceding: logicalColumns
          .slice(0, index)
          .map((item) => item.id)
          .reverse(),
        following: logicalColumns.slice(index + 1).map((item) => item.id),
        expiresAt: Date.now() + columnUndoMilliseconds,
      },
    ]);
    setLayout((current) => ({
      ...current,
      columns: current.columns.filter((item) => item.id !== id),
    }));
  };
  const undo = (id: string) => {
    const item = currentRemoved.current.find((entry) => entry.column.id === id);
    if (item === undefined || item.expiresAt <= Date.now()) return;
    // Consume synchronously: a rapid double click cannot restore twice.
    currentRemoved.current = currentRemoved.current.filter((entry) => entry !== item);
    setRemoved(currentRemoved.current);
    setLayout((current) => ({ ...current, columns: restoreColumn(current.columns, item) }));
  };
  return { removed, remove, undo };
}
