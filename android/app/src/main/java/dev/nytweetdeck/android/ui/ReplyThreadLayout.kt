package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.ConversationReply

internal const val MAX_REPLY_VISUAL_DEPTH = 5
private const val MAX_REPLY_ANCESTRY_TRAVERSAL = 64

internal data class ReplyThreadPosition(
    val reply: ConversationReply,
    val ancestorReplyIds: List<String>,
    val hasDescendants: Boolean,
) {
    val depth: Int
        get() = ancestorReplyIds.size
}

/**
 * Preserves X's response/ranking order and only annotates each reply with safe visual ancestry.
 * Invalid or cyclic ancestry is flattened instead of risking an unbounded traversal.
 */
internal fun buildReplyThreadLayout(
    focalPostId: String,
    replies: List<ConversationReply>,
    maxVisualDepth: Int = MAX_REPLY_VISUAL_DEPTH,
): List<ReplyThreadPosition> {
    require(maxVisualDepth >= 0) { "返信の最大表示深度が不正です。" }
    val replyById = LinkedHashMap<String, ConversationReply>()
    replies.forEach { replyById.putIfAbsent(it.post.id, it) }
    val ancestorIdsByReply = replies.associate { reply ->
        reply.post.id to resolveReplyAncestors(
            focalPostId = focalPostId,
            reply = reply,
            replyById = replyById,
            maxVisualDepth = maxVisualDepth,
        )
    }
    val repliesWithDescendants = ancestorIdsByReply.values.flatten().toHashSet()
    return replies.map { reply ->
        ReplyThreadPosition(
            reply = reply,
            ancestorReplyIds = ancestorIdsByReply[reply.post.id].orEmpty(),
            hasDescendants = reply.post.id in repliesWithDescendants,
        )
    }
}

private fun resolveReplyAncestors(
    focalPostId: String,
    reply: ConversationReply,
    replyById: Map<String, ConversationReply>,
    maxVisualDepth: Int,
): List<String> {
    val nearestFirst = ArrayList<String>()
    val visited = hashSetOf(reply.post.id)
    var parentId = reply.post.replyToPostId
    var traversed = 0
    while (parentId != null && parentId != focalPostId && traversed < MAX_REPLY_ANCESTRY_TRAVERSAL) {
        if (!visited.add(parentId)) return emptyList()
        val parent = replyById[parentId] ?: return emptyList()
        nearestFirst += parent.post.id
        parentId = parent.post.replyToPostId
        traversed++
    }
    if (parentId != null && parentId != focalPostId) {
        return nearestFirst.take(maxVisualDepth).asReversed()
    }
    return nearestFirst.take(maxVisualDepth).asReversed()
}
