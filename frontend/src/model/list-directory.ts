import { fetchWithTimeout } from "./fetch-with-timeout";

export type ListSource = "mine" | "suggested" | "search";

export interface ListOption {
  id: string;
  name: string;
  description: string | null;
  ownerName: string | null;
  ownerUsername: string | null;
  memberCount: number;
  subscriberCount: number;
  source: ListSource;
}

export interface ListDirectoryPage {
  lists: ListOption[];
  nextCursor: string | null;
}

export async function loadListDirectory(
  accountId: string,
  scope: ListSource,
  query?: string,
): Promise<ListDirectoryPage> {
  const params = new URLSearchParams({ accountId, scope });
  if (query !== undefined) params.set("query", query);
  const response = await fetchWithTimeout(`/api/v1/lists?${params}`);
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const value = (await response.json()) as unknown;
  if (!isListDirectoryPage(value)) throw new Error("Invalid list directory response");
  return value;
}

export function uniqueLists(lists: readonly ListOption[]): ListOption[] {
  const byId = new Map<string, ListOption>();
  for (const list of lists) {
    if (!byId.has(list.id)) byId.set(list.id, list);
  }
  return [...byId.values()];
}

export function sameListOptions(first: readonly ListOption[], second: readonly ListOption[]) {
  return (
    first.length === second.length &&
    first.every((item, index) => {
      const other = second[index];
      return other !== undefined && JSON.stringify(item) === JSON.stringify(other);
    })
  );
}

function isListDirectoryPage(value: unknown): value is ListDirectoryPage {
  if (!isRecord(value) || !Array.isArray(value.lists)) return false;
  if (value.nextCursor !== null && typeof value.nextCursor !== "string") return false;
  return value.lists.every(isListOption);
}

function isListOption(value: unknown): value is ListOption {
  if (!isRecord(value)) return false;
  return (
    typeof value.id === "string" &&
    typeof value.name === "string" &&
    isNullableString(value.description) &&
    isNullableString(value.ownerName) &&
    isNullableString(value.ownerUsername) &&
    typeof value.memberCount === "number" &&
    Number.isFinite(value.memberCount) &&
    typeof value.subscriberCount === "number" &&
    Number.isFinite(value.subscriberCount) &&
    (value.source === "mine" || value.source === "suggested" || value.source === "search")
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}
