import type { TimelinePost } from "./timeline";

export const userSuppressedEventName = "nytweetdeck:user-suppressed";

export interface UserSuppression {
  accountId: string;
  userId: string;
}

export function notifyUserSuppressed(suppression: UserSuppression): void {
  window.dispatchEvent(
    new CustomEvent<UserSuppression>(userSuppressedEventName, { detail: suppression }),
  );
}

export function subscribeUserSuppressed(
  listener: (suppression: UserSuppression) => void,
): () => void {
  const handle = (event: Event) => {
    const detail = (event as CustomEvent<unknown>).detail;
    if (!isUserSuppression(detail)) return;
    listener(detail);
  };
  window.addEventListener(userSuppressedEventName, handle);
  return () => window.removeEventListener(userSuppressedEventName, handle);
}

export function removePostsByUser(posts: TimelinePost[], userId: string): TimelinePost[] {
  return posts.filter((post) => post.author.id !== userId && post.repostedBy?.id !== userId);
}

function isUserSuppression(value: unknown): value is UserSuppression {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.accountId === "string" && typeof candidate.userId === "string";
}
