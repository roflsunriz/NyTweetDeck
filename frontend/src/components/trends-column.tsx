import { Search, X } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useId, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";
import { fetchWithTimeout } from "../model/fetch-with-timeout";

interface Trend {
  name: string;
  description: string | null;
  rank: string | null;
  url: string;
  domainContext: string | null;
  metaDescription: string | null;
}

interface TrendPage {
  trends: Trend[];
  nextCursor: string | null;
}

export function TrendsColumn({
  accountId,
  translation,
  onSelect,
  filterQuery = "",
  searchHistory = [],
  onFilterChange,
  onRememberFilter,
  requestTimeoutMilliseconds = 15_000,
}: {
  accountId: string | null;
  translation: Translation;
  onSelect?: (query: string) => void;
  filterQuery?: string;
  searchHistory?: readonly string[];
  onFilterChange?: (query: string) => void;
  onRememberFilter?: (query: string) => void;
  requestTimeoutMilliseconds?: number;
}) {
  const [trends, setTrends] = useState<Trend[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const loadingRef = useRef(false);
  const loadMoreRef = useRef<HTMLButtonElement | null>(null);
  const historyId = useId();

  const load = useCallback(
    async (nextCursor?: string) => {
      if (accountId === null || loadingRef.current) {
        return;
      }
      loadingRef.current = true;
      setLoading(true);
      setError(false);
      try {
        const params = new URLSearchParams({ accountId });
        if (nextCursor !== undefined) {
          params.set("cursor", nextCursor);
        }
        const response = await fetchWithTimeout(
          `/api/v1/trends?${params}`,
          {},
          requestTimeoutMilliseconds,
        );
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        const page = (await response.json()) as TrendPage;
        setTrends((current) =>
          nextCursor === undefined
            ? page.trends
            : [
                ...current,
                ...page.trends.filter((trend) => !current.some((item) => item.name === trend.name)),
              ],
        );
        setCursor(page.nextCursor);
      } catch {
        setError(true);
      } finally {
        loadingRef.current = false;
        setLoading(false);
      }
    },
    [accountId, requestTimeoutMilliseconds],
  );

  useEffect(() => {
    setTrends([]);
    setCursor(null);
    void load();
  }, [load]);

  useEffect(() => {
    const target = loadMoreRef.current;
    if (target === null || cursor === null || typeof IntersectionObserver === "undefined") {
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          void load(cursor);
        }
      },
      { rootMargin: "240px 0px" },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [cursor, load]);

  if (accountId === null) {
    return (
      <div className="column-message">
        <strong>{translation.loginRequired}</strong>
        <p>{translation.loginRequiredDescription}</p>
      </div>
    );
  }
  const visibleTrends = filterTrends(trends, filterQuery);
  const rememberFilter = (event: FormEvent) => {
    event.preventDefault();
    const normalized = filterQuery.trim();
    if (normalized !== filterQuery) {
      onFilterChange?.(normalized);
    }
    onRememberFilter?.(normalized);
  };
  return (
    <div className="trend-column-content">
      <form className="trend-filter-form" onSubmit={rememberFilter}>
        <label htmlFor={`${historyId}-input`}>{translation.trendFilterLabel}</label>
        <span className="trend-filter-input">
          <Search aria-hidden="true" size={16} />
          <input
            id={`${historyId}-input`}
            data-testid="trend-filter-input"
            type="search"
            list={historyId}
            maxLength={100}
            placeholder={translation.trendFilterPlaceholder}
            value={filterQuery}
            onChange={(event) => onFilterChange?.(event.target.value)}
          />
          {filterQuery.length > 0 && (
            <button
              type="button"
              aria-label={translation.clearTrendFilter}
              onClick={() => onFilterChange?.("")}
            >
              <X aria-hidden="true" size={15} />
            </button>
          )}
        </span>
        <datalist id={historyId}>
          {searchHistory.map((query) => (
            <option key={query} value={query} />
          ))}
        </datalist>
      </form>
      <div className="trend-list">
        {loading && trends.length === 0 ? (
          <p className="column-message">{translation.loading}</p>
        ) : error && trends.length === 0 ? (
          <div className="column-message">
            <strong>{translation.trendLoadError}</strong>
            <button className="secondary-button" type="button" onClick={() => load()}>
              {translation.retry}
            </button>
          </div>
        ) : trends.length === 0 ? (
          <p className="column-message">{translation.noTrends}</p>
        ) : (
          <>
            {visibleTrends.length === 0 && (
              <p className="column-message">{translation.noFilteredTrends}</p>
            )}
            {visibleTrends.map((trend) => {
              const fallbackRank = trends.indexOf(trend) + 1;
              return (
                <button
                  type="button"
                  key={trend.name}
                  className="deck-feed-item trend-item"
                  data-trend-rank={trend.rank ?? fallbackRank}
                  onClick={() => onSelect?.(trend.name)}
                >
                  <small>
                    {trend.rank ?? fallbackRank} ·{" "}
                    {trend.domainContext ?? trend.metaDescription ?? translation.trends}
                  </small>
                  <strong>{trend.name}</strong>
                  {trend.description !== null && <span>{trend.description}</span>}
                </button>
              );
            })}
            {cursor !== null && (
              <button
                ref={loadMoreRef}
                className="load-more-button"
                type="button"
                disabled={loading}
                onClick={() => load(cursor)}
              >
                {loading ? translation.loading : translation.loadMore}
              </button>
            )}
            {error && <p className="inline-error">{translation.trendLoadError}</p>}
          </>
        )}
      </div>
    </div>
  );
}

export function filterTrends(trends: readonly Trend[], query: string): Trend[] {
  const normalized = query.trim().toLocaleLowerCase();
  if (normalized.length === 0) {
    return [...trends];
  }
  return trends.filter((trend) =>
    [trend.name, trend.description, trend.domainContext, trend.metaDescription].some((value) =>
      value?.toLocaleLowerCase().includes(normalized),
    ),
  );
}
