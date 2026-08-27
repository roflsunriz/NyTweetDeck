import { CalendarDays, Link as LinkIcon, MapPin } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import { defaultDisplayPreferences, type DisplayPreferences } from "../model/layout";
import { Modal } from "./modal";
import { PostCard, type TimelinePost } from "./post-card";
import { usePostTranslationSettings } from "./post-translation-context";
import { PostDetailDialog } from "./post-detail-dialog";
import { filterPosts, type PostTypeFilter } from "./post-filter";

type ProfileTab = "all" | "posts" | "highlights" | "replies" | "media";

interface RelatedUser {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
}

interface UserProfile {
  id: string;
  username: string;
  displayName: string;
  description: string | null;
  avatarUrl: string | null;
  bannerUrl: string | null;
  createdAt: string | null;
  location: string | null;
  website: string | null;
  followingCount: number;
  followerCount: number;
  mutualFollowerCount: number;
  mutualFollowers: RelatedUser[];
  verified: boolean;
  following: boolean;
  followsYou: boolean;
}

interface TimelinePage {
  posts: TimelinePost[];
  nextCursor: string | null;
}

export function UserProfileDialog({
  userId,
  accountId,
  translation,
  onClose,
  display = defaultDisplayPreferences,
}: {
  userId: string;
  accountId: string;
  translation: Translation;
  onClose: () => void;
  display?: DisplayPreferences;
}) {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [tab, setTab] = useState<ProfileTab>("all");
  const [posts, setPosts] = useState<TimelinePost[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [mediaFilter, setMediaFilter] = useState<PostTypeFilter | "all">("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [selectedPostId, setSelectedPostId] = useState<string | null>(null);
  const { locale } = usePostTranslationSettings();

  const loadTimeline = useCallback(
    async (nextCursor?: string) => {
      const params = new URLSearchParams({ accountId, tab, language: locale });
      if (nextCursor !== undefined) params.set("cursor", nextCursor);
      const response = await fetch(
        `/api/v1/users/${encodeURIComponent(userId)}/timeline?${params}`,
      );
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const page = (await response.json()) as TimelinePage;
      setPosts((current) =>
        nextCursor === undefined
          ? page.posts
          : [
              ...current,
              ...page.posts.filter((post) => !current.some((item) => item.id === post.id)),
            ],
      );
      setCursor(page.nextCursor);
    },
    [accountId, locale, tab, userId],
  );

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(false);
    const profileRequest = fetch(
      `/api/v1/users/${encodeURIComponent(userId)}?accountId=${encodeURIComponent(accountId)}&language=${encodeURIComponent(locale)}`,
      { signal: controller.signal },
    ).then(async (response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      setProfile((await response.json()) as UserProfile);
    });
    void Promise.all([profileRequest, loadTimeline()])
      .catch((loadError) => {
        if (!(loadError instanceof DOMException && loadError.name === "AbortError")) setError(true);
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [accountId, loadTimeline, locale, userId]);

  const visiblePosts =
    tab === "media" && mediaFilter !== "all"
      ? filterPosts(posts, { types: [mediaFilter], excludeReposts: false })
      : posts;
  const tabs: Array<[ProfileTab, string]> = [
    ["all", translation.profileAll],
    ["posts", translation.profilePosts],
    ["highlights", translation.profileHighlights],
    ["replies", translation.profileReplies],
    ["media", translation.profileMedia],
  ];

  return (
    <Modal
      title={profile?.displayName ?? translation.userProfile}
      closeLabel={translation.close}
      onClose={onClose}
    >
      <div className="user-profile">
        {profile !== null && <ProfileHeader profile={profile} translation={translation} />}
        <div className="profile-tabs" role="tablist" aria-label={translation.userProfile}>
          {tabs.map(([value, label]) => (
            <button
              type="button"
              role="tab"
              aria-selected={tab === value}
              className={tab === value ? "active" : undefined}
              key={value}
              onClick={() => {
                setPosts([]);
                setCursor(null);
                setMediaFilter("all");
                setTab(value);
              }}
            >
              {label}
            </button>
          ))}
        </div>
        {tab === "media" && (
          <div className="profile-media-filter">
            {(["all", "image", "video"] as const).map((value) => (
              <button
                type="button"
                className={mediaFilter === value ? "active" : undefined}
                key={value}
                onClick={() => setMediaFilter(value)}
              >
                {value === "all"
                  ? translation.filterAll
                  : value === "image"
                    ? translation.filterImages
                    : translation.filterVideos}
              </button>
            ))}
          </div>
        )}
        {loading && posts.length === 0 ? (
          <p className="column-message">{translation.loading}</p>
        ) : error && posts.length === 0 ? (
          <p className="setup-error">{translation.profileLoadError}</p>
        ) : visiblePosts.length === 0 ? (
          <p className="column-message">{translation.noPosts}</p>
        ) : (
          visiblePosts.map((post) => (
            <PostCard
              key={post.id}
              post={post}
              accountId={accountId}
              translation={translation}
              display={display}
              onOpen={() => setSelectedPostId(post.id)}
              onOpenQuotedPost={setSelectedPostId}
            />
          ))
        )}
        {cursor !== null && (
          <button className="load-more-button" type="button" onClick={() => loadTimeline(cursor)}>
            {translation.loadMore}
          </button>
        )}
        {selectedPostId !== null && (
          <PostDetailDialog
            postId={selectedPostId}
            accountId={accountId}
            translation={translation}
            display={display}
            onClose={() => setSelectedPostId(null)}
            onOpenPost={setSelectedPostId}
          />
        )}
      </div>
    </Modal>
  );
}

function ProfileHeader({
  profile,
  translation,
}: {
  profile: UserProfile;
  translation: Translation;
}) {
  return (
    <header className="profile-header">
      <div className="profile-banner">
        {profile.bannerUrl !== null && <img src={profile.bannerUrl} alt="" />}
      </div>
      <div className="profile-identity">
        {profile.avatarUrl !== null ? (
          <img className="profile-avatar" src={profile.avatarUrl} alt="" />
        ) : (
          <span className="profile-avatar profile-avatar-placeholder" aria-hidden="true" />
        )}
        <div>
          <h2>{profile.displayName}</h2>
          <p>@{profile.username}</p>
        </div>
      </div>
      {profile.description !== null && <p className="profile-description">{profile.description}</p>}
      <div className="profile-details">
        {profile.location !== null && (
          <span>
            <MapPin aria-hidden="true" size={15} /> {profile.location}
          </span>
        )}
        {profile.website !== null && (
          <a href={profile.website} target="_blank" rel="noreferrer">
            <LinkIcon aria-hidden="true" size={15} /> {profile.website}
          </a>
        )}
        {profile.createdAt !== null && (
          <span>
            <CalendarDays aria-hidden="true" size={15} />
            {translation.joinedAt(new Date(profile.createdAt).toLocaleDateString())}
          </span>
        )}
      </div>
      <div className="profile-counts">
        <span>
          <strong>{profile.followingCount.toLocaleString()}</strong> {translation.followingCount}
        </span>
        <span>
          <strong>{profile.followerCount.toLocaleString()}</strong> {translation.followersCount}
        </span>
        <span>
          <strong>{profile.mutualFollowerCount.toLocaleString()}</strong>{" "}
          {translation.mutualFollowers}
        </span>
      </div>
      {profile.mutualFollowers.length > 0 && (
        <fieldset className="profile-mutual-avatars" aria-label={translation.mutualFollowers}>
          {profile.mutualFollowers
            .slice(0, 8)
            .map((user) =>
              user.avatarUrl !== null ? (
                <img
                  key={user.id}
                  src={user.avatarUrl}
                  alt={user.displayName}
                  title={`@${user.username}`}
                />
              ) : null,
            )}
        </fieldset>
      )}
    </header>
  );
}
