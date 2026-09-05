import { useCallback, useEffect, useRef, useState } from "react";
import type { TimelinePost } from "./timeline";
import { useOverlayRoute } from "./use-overlay-route";

interface DetailEntry {
  postId: string;
  initialPost?: TimelinePost;
}

/** Keeps all post-to-post navigation inside one detail route and one Back step per post. */
export function usePostDetailNavigation(
  postId: string,
  initialPost: TimelinePost | undefined,
  onClose: () => void,
) {
  const closeRoot = useOverlayRoute(`post/${encodeURIComponent(postId)}`, onClose);
  const [entry, setEntry] = useState<DetailEntry>({ postId, initialPost });
  const entries = useRef<DetailEntry[]>([{ postId, initialPost }]);
  const index = useRef(0);
  const token = useRef<unknown>(null);
  const initialPostRef = useRef(initialPost);
  initialPostRef.current = initialPost;

  useEffect(() => {
    const rootEntry = { postId, initialPost: initialPostRef.current };
    entries.current = [rootEntry];
    index.current = 0;
    setEntry(rootEntry);
    token.current = window.history.state?.nytdOverlayToken;
    window.history.replaceState({ ...window.history.state, nytdPostDetailIndex: 0 }, "");
    const onPopState = () => {
      if (window.history.state?.nytdOverlayToken !== token.current) return;
      const nextIndex: unknown = window.history.state?.nytdPostDetailIndex;
      if (typeof nextIndex !== "number" || !Number.isInteger(nextIndex)) return;
      const next = entries.current[nextIndex];
      if (next === undefined) return;
      index.current = nextIndex;
      setEntry(next);
    };
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, [postId]);

  const open = useCallback((nextId: string, post?: TimelinePost) => {
    if (entries.current[index.current]?.postId === nextId) return;
    const next = { postId: nextId, initialPost: post };
    entries.current = [...entries.current.slice(0, index.current + 1), next];
    index.current += 1;
    const url = new URL(window.location.href);
    url.hash = `/post/${encodeURIComponent(nextId)}`;
    window.history.pushState(
      { ...window.history.state, nytdPostDetailIndex: index.current },
      "",
      url.href,
    );
    setEntry(next);
  }, []);

  const close = useCallback(() => {
    if (window.history.state?.nytdOverlayToken !== token.current) return;
    if (index.current > 0) window.history.back();
    else closeRoot();
  }, [closeRoot]);
  return { ...entry, open, close };
}
