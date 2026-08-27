import { fetchWithTimeout } from "./fetch-with-timeout";
import {
  type AppLayout,
  isAppLayout,
  layoutStorageKey,
  loadLayout,
  type StorageLike,
} from "./layout";

const sharedLayoutUrl = "/api/v1/settings/layout";

export interface SharedLayoutSnapshot {
  revision: number;
  layout: AppLayout;
}

export class SharedLayoutConflictError extends Error {
  constructor(readonly snapshot: SharedLayoutSnapshot) {
    super("The shared layout changed in another NyTweetDeck window.");
  }
}

type LayoutFetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export async function initializeSharedLayout(
  legacyStorage: StorageLike,
  fetcher: LayoutFetcher = fetchWithTimeout,
): Promise<SharedLayoutSnapshot> {
  const existing = await requestSharedLayout(fetcher);
  if (existing !== null) {
    legacyStorage.removeItem(layoutStorageKey);
    return existing;
  }

  const legacyLayout = loadLayout(legacyStorage);
  try {
    const migrated = await saveSharedLayout(legacyLayout, 0, fetcher);
    legacyStorage.removeItem(layoutStorageKey);
    return migrated;
  } catch (error) {
    if (error instanceof SharedLayoutConflictError) {
      legacyStorage.removeItem(layoutStorageKey);
      return error.snapshot;
    }
    throw error;
  }
}

export async function loadSharedLayout(
  fetcher: LayoutFetcher = fetchWithTimeout,
): Promise<SharedLayoutSnapshot> {
  const snapshot = await requestSharedLayout(fetcher);
  if (snapshot === null) {
    throw new Error("The shared layout has not been initialized.");
  }
  return snapshot;
}

export async function saveSharedLayout(
  layout: AppLayout,
  expectedRevision: number,
  fetcher: LayoutFetcher = fetchWithTimeout,
): Promise<SharedLayoutSnapshot> {
  const response = await fetcher(sharedLayoutUrl, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ expectedRevision, layout }),
  });
  if (response.status === 409) {
    throw new SharedLayoutConflictError(await readSnapshot(response));
  }
  if (!response.ok) {
    throw new Error(`Could not save shared layout: HTTP ${response.status}`);
  }
  return readSnapshot(response);
}

async function requestSharedLayout(fetcher: LayoutFetcher): Promise<SharedLayoutSnapshot | null> {
  const response = await fetcher(sharedLayoutUrl, {
    headers: { Accept: "application/json" },
  });
  if (response.status === 204) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`Could not load shared layout: HTTP ${response.status}`);
  }
  return readSnapshot(response);
}

async function readSnapshot(response: Response): Promise<SharedLayoutSnapshot> {
  const value: unknown = await response.json();
  if (
    !isRecord(value) ||
    !Number.isInteger(value.revision) ||
    Number(value.revision) < 1 ||
    !isAppLayout(value.layout)
  ) {
    throw new Error("The shared layout response is invalid.");
  }
  return { revision: Number(value.revision), layout: value.layout };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
