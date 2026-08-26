import { useCallback, useEffect, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";

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
  requestTimeoutMilliseconds = 15_000,
}: {
  accountId: string | null;
  translation: Translation;
  onSelect?: (query: string) => void;
  requestTimeoutMilliseconds?: number;
}) {
  const [trends, setTrends] = useState<Trend[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const loadingRef = useRef(false);
  const loadMoreRef = useRef<HTMLButtonElement | null>(null);

  const load = useCallback(
    async (nextCursor?: string) => {
      if (accountId === null || loadingRef.current) {
        return;
      }
      loadingRef.current = true;
      setLoading(true);
      setError(false);
      const controller = new AbortController();
      const timeout = window.setTimeout(
        () => controller.abort(new DOMException("Trend request timed out", "TimeoutError")),
        Math.max(1, requestTimeoutMilliseconds),
      );
      try {
        const params = new URLSearchParams({ accountId });
        if (nextCursor !== undefined) {
          params.set("cursor", nextCursor);
        }
        const response = await fetch(`/api/v1/trends?${params}`, {
          signal: controller.signal,
        });
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
        window.clearTimeout(timeout);
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
  if (loading && trends.length === 0) {
    return <div className="column-message">{translation.loading}</div>;
  }
  if (error && trends.length === 0) {
    return (
      <div className="column-message">
        <strong>{translation.trendLoadError}</strong>
        <button className="secondary-button" type="button" onClick={() => load()}>
          {translation.retry}
        </button>
      </div>
    );
  }
  if (trends.length === 0) {
    return <div className="column-message">{translation.noTrends}</div>;
  }
  return (
    <div className="trend-list">
      {trends.map((trend, index) => (
        <button
          type="button"
          key={trend.name}
          className="deck-feed-item trend-item"
          data-trend-rank={trend.rank ?? index + 1}
          onClick={() => onSelect?.(trend.name)}
        >
          <small>
            {trend.rank ?? index + 1} ·{" "}
            {trend.domainContext ?? trend.metaDescription ?? translation.trends}
          </small>
          <strong>{trend.name}</strong>
          {trend.description !== null && <span>{trend.description}</span>}
        </button>
      ))}
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
    </div>
  );
}
