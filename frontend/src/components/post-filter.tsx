import { FileText, Images, ListFilter, Video } from "lucide-react";
import type { Translation } from "../i18n/translations";
import type { TimelinePost } from "./post-card";

export type PostFilter = "all" | "text" | "image" | "video";

export function PostFilterBar({
  value,
  translation,
  onChange,
}: {
  value: PostFilter;
  translation: Translation;
  onChange: (value: PostFilter) => void;
}) {
  const filters = [
    ["all", translation.filterAll, ListFilter],
    ["text", translation.filterText, FileText],
    ["image", translation.filterImages, Images],
    ["video", translation.filterVideos, Video],
  ] as const;
  return (
    <div className="post-filter" role="toolbar" aria-label={translation.filterPosts}>
      {filters.map(([filter, label, Icon]) => (
        <button
          className={value === filter ? "active" : undefined}
          data-post-filter={filter}
          key={filter}
          type="button"
          aria-pressed={value === filter}
          onClick={() => onChange(filter)}
        >
          <Icon aria-hidden="true" size={15} />
          <span>{label}</span>
        </button>
      ))}
    </div>
  );
}

export function filterPosts(posts: TimelinePost[], filter: PostFilter): TimelinePost[] {
  if (filter === "all") return posts;
  if (filter === "text") return posts.filter((post) => post.media.length === 0);
  if (filter === "image") {
    return posts.filter((post) => post.media.some((media) => media.type === "photo"));
  }
  return posts.filter((post) =>
    post.media.some((media) => media.type === "video" || media.type === "animated_gif"),
  );
}
