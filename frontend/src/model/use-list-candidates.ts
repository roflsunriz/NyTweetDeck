import { useCallback, useEffect, useRef, useState } from "react";
import { loadListDirectory, type ListOption, sameListOptions, uniqueLists } from "./list-directory";

export interface ListCandidatesState {
  options: readonly ListOption[];
  ready: boolean;
  error: boolean;
}

interface AccountListCandidates extends ListCandidatesState {
  accountId: string;
}

const emptyCandidates: AccountListCandidates = {
  accountId: "",
  options: [],
  ready: false,
  error: false,
};

export function useListCandidates(
  accountIds: readonly string[] | null,
  activeAccountId: string | null,
): ListCandidatesState {
  const cacheRef = useRef(new Map<string, AccountListCandidates>());
  const inFlightRef = useRef(new Map<string, Promise<void>>());
  const activeAccountIdRef = useRef(activeAccountId);
  const [snapshot, setSnapshot] = useState<AccountListCandidates>(emptyCandidates);
  activeAccountIdRef.current = activeAccountId;

  const refresh = useCallback((accountId: string) => {
    const existingRequest = inFlightRef.current.get(accountId);
    if (existingRequest !== undefined) return existingRequest;
    const request = refreshAccountCandidates(accountId, cacheRef.current).then((next) => {
      if (next !== null && activeAccountIdRef.current === accountId) setSnapshot(next);
    });
    inFlightRef.current.set(accountId, request);
    void request.finally(() => inFlightRef.current.delete(accountId));
    return request;
  }, []);

  useEffect(() => {
    for (const accountId of accountIds ?? []) void refresh(accountId);
  }, [accountIds, refresh]);

  useEffect(() => {
    if (activeAccountId === null) {
      setSnapshot(emptyCandidates);
      return;
    }
    setSnapshot(cacheRef.current.get(activeAccountId) ?? emptyCandidates);
    void refresh(activeAccountId);
  }, [activeAccountId, refresh]);

  useEffect(() => {
    if (activeAccountId === null) return;
    const refreshActive = () => void refresh(activeAccountId);
    const refreshWhenVisible = () => {
      if (document.visibilityState === "visible") refreshActive();
    };
    window.addEventListener("focus", refreshActive);
    document.addEventListener("visibilitychange", refreshWhenVisible);
    return () => {
      window.removeEventListener("focus", refreshActive);
      document.removeEventListener("visibilitychange", refreshWhenVisible);
    };
  }, [activeAccountId, refresh]);

  if (activeAccountId === null) return emptyCandidates;
  return snapshot.accountId === activeAccountId
    ? snapshot
    : (cacheRef.current.get(activeAccountId) ?? emptyCandidates);
}

async function refreshAccountCandidates(
  accountId: string,
  cache: Map<string, AccountListCandidates>,
): Promise<AccountListCandidates | null> {
  const previous = cache.get(accountId);
  const [mine, suggested] = await Promise.allSettled([
    loadListDirectory(accountId, "mine"),
    loadListDirectory(accountId, "suggested"),
  ]);
  if (mine.status === "rejected" && suggested.status === "rejected") {
    if (previous?.ready === true) return null;
    const failed = { accountId, options: [], ready: false, error: true };
    cache.set(accountId, failed);
    return failed;
  }
  const previousOptions = previous?.options ?? [];
  const nextOptions = uniqueLists([
    ...(mine.status === "fulfilled"
      ? mine.value.lists
      : previousOptions.filter((option) => option.source === "mine")),
    ...(suggested.status === "fulfilled"
      ? suggested.value.lists
      : previousOptions.filter((option) => option.source === "suggested")),
  ]);
  if (
    previous?.ready === true &&
    previous.error === false &&
    sameListOptions(previous.options, nextOptions)
  ) {
    return null;
  }
  const next = { accountId, options: nextOptions, ready: true, error: false };
  cache.set(accountId, next);
  return next;
}
