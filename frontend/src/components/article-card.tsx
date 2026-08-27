import { ExternalLink, X } from "lucide-react";
import { useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import { loadPostDetail } from "../model/post-detail";
import type { TimelineArticle } from "./post-card";
import { usePostTranslationSettings } from "./post-translation-context";

interface ArticleCardProps {
  article: TimelineArticle;
  postId: string;
  accountId: string;
  translation: Translation;
}

export function ArticleCard({ article, postId, accountId, translation }: ArticleCardProps) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <button
        className="x-article-card"
        type="button"
        aria-label={`${translation.openArticle}: ${article.title}`}
        onClick={() => setOpen(true)}
      >
        {article.coverImageUrl !== null && (
          <img src={article.coverImageUrl} alt="" loading="lazy" />
        )}
        <span className="x-article-card-content">
          <small>{translation.xArticle}</small>
          <strong>{article.title}</strong>
          {article.previewText !== null && <span>{article.previewText}</span>}
        </span>
      </button>
      {open && (
        <ArticleReader
          initialArticle={article}
          postId={postId}
          accountId={accountId}
          translation={translation}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}

function ArticleReader({
  initialArticle,
  postId,
  accountId,
  translation,
  onClose,
}: {
  initialArticle: TimelineArticle;
  postId: string;
  accountId: string;
  translation: Translation;
  onClose: () => void;
}) {
  const [article, setArticle] = useState(initialArticle);
  const [loading, setLoading] = useState(initialArticle.body === null);
  const [error, setError] = useState(false);
  const { locale } = usePostTranslationSettings();

  useEffect(() => {
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      onClose();
    };
    window.addEventListener("keydown", handleEscape, true);
    return () => window.removeEventListener("keydown", handleEscape, true);
  }, [onClose]);

  useEffect(() => {
    if (initialArticle.body !== null) return;
    let active = true;
    setLoading(true);
    setError(false);
    void loadPostDetail(accountId, postId, locale)
      .then((detail) => {
        if (!active) return;
        const loaded = detail.post.article;
        if (loaded === undefined || loaded === null || loaded.body === null) {
          throw new Error("Article body missing");
        }
        setArticle(loaded);
      })
      .catch(() => {
        if (active) setError(true);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [accountId, initialArticle.body, locale, postId]);

  return (
    <div className="article-reader-backdrop">
      <article
        className="article-reader"
        aria-modal="true"
        role="dialog"
        aria-label={article.title}
      >
        <header className="article-reader-toolbar">
          <span>{translation.xArticle}</span>
          <a href={article.url} target="_blank" rel="noreferrer" aria-label={translation.openOnX}>
            <ExternalLink aria-hidden="true" size={18} />
          </a>
          <button type="button" aria-label={translation.closeArticle} onClick={onClose}>
            <X aria-hidden="true" size={20} />
          </button>
        </header>
        {article.coverImageUrl !== null && (
          <img className="article-reader-cover" src={article.coverImageUrl} alt="" />
        )}
        <div className="article-reader-content">
          <h2>{article.title}</h2>
          {loading ? (
            <p>{translation.loading}</p>
          ) : error || article.body === null ? (
            <p className="setup-error">{translation.articleLoadError}</p>
          ) : (
            <div className="article-reader-body">{article.body}</div>
          )}
        </div>
      </article>
    </div>
  );
}
