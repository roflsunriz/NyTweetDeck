export interface ReplyRelationshipSource {
  id: string;
  replyToPostId: string | null;
}

export interface ReplyThreadLayoutItem<T extends ReplyRelationshipSource> {
  reply: T;
  depth: number;
  ancestorIds: string[];
  ancestorLines: boolean[];
  isLastSibling: boolean;
  hasChildren: boolean;
  depthCapped: boolean;
}

export const MAX_REPLY_THREAD_DEPTH = 6;

export function buildReplyThreadLayout<T extends ReplyRelationshipSource>(
  replies: readonly T[],
  focalPostId: string,
): Array<ReplyThreadLayoutItem<T>> {
  const idCounts = new Map<string, number>();
  for (const reply of replies) idCounts.set(reply.id, (idCounts.get(reply.id) ?? 0) + 1);

  const indexById = new Map<string, number>();
  replies.forEach((reply, index) => {
    if (idCounts.get(reply.id) === 1) indexById.set(reply.id, index);
  });

  const parentById = new Map<string, string | null>();
  for (const reply of replies) {
    if (idCounts.get(reply.id) !== 1) continue;
    const requestedParent = reply.replyToPostId;
    const validParent =
      requestedParent !== null &&
      requestedParent !== focalPostId &&
      requestedParent !== reply.id &&
      indexById.has(requestedParent);
    parentById.set(reply.id, validParent ? requestedParent : null);
  }
  breakCycles(parentById);

  const effectiveParents = replies.map((reply) => parentById.get(reply.id) ?? null);
  const lastSiblingIndex = new Map<string | null, number>();
  effectiveParents.forEach((parentId, index) => {
    lastSiblingIndex.set(parentId, index);
  });
  const parentsWithChildren = new Set(
    effectiveParents.filter((parentId): parentId is string => parentId !== null),
  );

  return replies.map((reply, index) => {
    const fullAncestorIds = resolveAncestors(reply.id, parentById);
    const ancestorIds = fullAncestorIds.slice(-MAX_REPLY_THREAD_DEPTH);
    return {
      reply,
      depth: ancestorIds.length,
      ancestorIds,
      ancestorLines: ancestorIds.map((ancestorId) => {
        const ancestorIndex = indexById.get(ancestorId);
        if (ancestorIndex === undefined) return false;
        const ancestorParent = effectiveParents[ancestorIndex] ?? null;
        return lastSiblingIndex.get(ancestorParent) !== ancestorIndex;
      }),
      isLastSibling: lastSiblingIndex.get(effectiveParents[index] ?? null) === index,
      hasChildren: parentsWithChildren.has(reply.id),
      depthCapped: fullAncestorIds.length > MAX_REPLY_THREAD_DEPTH,
    };
  });
}

function breakCycles(parentById: Map<string, string | null>): void {
  const resolved = new Set<string>();
  for (const start of parentById.keys()) {
    if (resolved.has(start)) continue;
    const path: string[] = [];
    const pathIndex = new Map<string, number>();
    let cursor: string | null = start;
    while (cursor !== null && !resolved.has(cursor)) {
      const cycleStart = pathIndex.get(cursor);
      if (cycleStart !== undefined) {
        for (const cycleId of path.slice(cycleStart)) parentById.set(cycleId, null);
        break;
      }
      pathIndex.set(cursor, path.length);
      path.push(cursor);
      cursor = parentById.get(cursor) ?? null;
    }
    for (const id of path) resolved.add(id);
  }
}

function resolveAncestors(
  replyId: string,
  parentById: ReadonlyMap<string, string | null>,
): string[] {
  const ancestors: string[] = [];
  let cursor = parentById.get(replyId) ?? null;
  while (cursor !== null && ancestors.length <= parentById.size) {
    ancestors.push(cursor);
    cursor = parentById.get(cursor) ?? null;
  }
  ancestors.reverse();
  return ancestors;
}
