import { FileText, Images, ListFilter, RepeatOff, Video } from "lucide-react";
import type { Translation } from "../i18n/translations";
import type { TimelinePost } from "./post-card";

export type PostTypeFilter = "text" | "image" | "video";

export interface PostFilter {
  types: PostTypeFilter[];
  excludeReposts: boolean;
}

export function createDefaultPostFilter(): PostFilter {
  return { types: [], excludeReposts: false };
}

export function PostFilterBar({
  value,
  translation,
  onChange,
}: {
  value: PostFilter;
  translation: Translation;
  onChange: (value: PostFilter) => void;
}) {
  const typeFilters = [
    ["text", translation.filterText, FileText],
    ["image", translation.filterImages, Images],
    ["video", translation.filterVideos, Video],
  ] as const;
  return (
    <div className="post-filter" role="toolbar" aria-label={translation.filterPosts}>
      <button
        className={value.types.length === 0 ? "active" : undefined}
        data-post-filter="all"
        type="button"
        aria-label={translation.filterAll}
        aria-pressed={value.types.length === 0}
        title={translation.filterAll}
        onClick={() => onChange({ ...value, types: [] })}
      >
        <ListFilter aria-hidden="true" size={17} />
      </button>
      {typeFilters.map(([filter, label, Icon]) => {
        const active = value.types.includes(filter);
        return (
          <button
            className={active ? "active" : undefined}
            data-post-filter={filter}
            key={filter}
            type="button"
            aria-label={label}
            aria-pressed={active}
            title={label}
            onClick={() =>
              onChange({
                ...value,
                types: active
                  ? value.types.filter((current) => current !== filter)
                  : [...value.types, filter],
              })
            }
          >
            <Icon aria-hidden="true" size={17} />
          </button>
        );
      })}
      <button
        className={`post-filter-exclude${value.excludeReposts ? " active" : ""}`}
        data-post-filter="exclude-reposts"
        type="button"
        aria-label={translation.filterExcludeReposts}
        aria-pressed={value.excludeReposts}
        title={translation.filterExcludeReposts}
        onClick={() => onChange({ ...value, excludeReposts: !value.excludeReposts })}
      >
        <RepeatOff aria-hidden="true" size={17} />
      </button>
    </div>
  );
}

export function filterPosts(posts: TimelinePost[], filter: PostFilter): TimelinePost[] {
  return posts.filter((post) => {
    if (filter.excludeReposts && post.repostedBy !== null) {
      return false;
    }
    return filter.types.length === 0 || filter.types.some((type) => matchesType(post, type));
  });
}

function matchesType(post: TimelinePost, type: PostTypeFilter): boolean {
  if (type === "text") return post.media.length === 0;
  if (type === "image") return post.media.some((media) => media.type === "photo");
  return post.media.some((media) => media.type === "video" || media.type === "animated_gif");
}
