export interface TimelinePost {
  id: string;
  text: string;
  language: string | null;
  createdAt: string | null;
  author: TimelineAuthor;
  repostedBy: TimelineAuthor | null;
  replyCount: number;
  repostCount: number;
  quoteCount: number;
  likeCount: number;
  bookmarkCount: number;
  viewCount: number;
  liked: boolean;
  reposted: boolean;
  bookmarked: boolean;
  replyToPostId: string | null;
  replyToUsername: string | null;
  quotedPost: EmbeddedPost | null;
  preTranslated?: PreTranslatedPost | null;
  communityNote?: CommunityNote | null;
  article?: TimelineArticle | null;
  media: TimelineMedia[];
}

export interface TimelinePage {
  posts: TimelinePost[];
  nextCursor: string | null;
}

export interface TimelineAuthor {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  verified: boolean;
}

export interface TimelineArticle {
  id: string;
  title: string;
  previewText: string | null;
  body: string | null;
  coverImageUrl: string | null;
  url: string;
}

export interface TimelineMedia {
  id: string;
  type: string;
  url: string;
  previewUrl: string;
}

export interface PreTranslatedPost {
  text: string;
  sourceLanguage: string | null;
  targetLanguage: string;
  provider: "Grok";
}

export interface CommunityNote {
  title: string | null;
  text: string | null;
  footer: string | null;
  sources?: Array<{ fromIndex: number; toIndex: number; url: string }>;
}

export interface EmbeddedPost {
  id: string;
  text: string;
  language: string | null;
  createdAt: string | null;
  author: TimelineAuthor;
  preTranslated?: PreTranslatedPost | null;
  article?: TimelineArticle | null;
  media: TimelineMedia[];
}
