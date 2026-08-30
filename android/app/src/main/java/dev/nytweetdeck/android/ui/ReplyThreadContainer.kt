package dev.nytweetdeck.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val THREAD_INDENT = 12.dp

@Composable
internal fun ReplyThreadContainer(
    position: ReplyThreadPosition,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val connectorWidth = THREAD_INDENT * (position.depth + 1)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag("reply-thread-row-${position.reply.post.id}"),
    ) {
        ReplyThreadConnectors(position, Modifier.width(connectorWidth).fillMaxHeight())
        Box(Modifier.weight(1f).testTag("reply-thread-content-${position.reply.post.id}")) {
            content()
        }
    }
}

@Composable
private fun ReplyThreadConnectors(
    position: ReplyThreadPosition,
    modifier: Modifier,
) {
    val color = MaterialTheme.colorScheme.outlineVariant
    val layoutDirection = LocalLayoutDirection.current
    Canvas(modifier.testTag("reply-thread-connectors-${position.reply.post.id}")) {
        val indentPx = THREAD_INDENT.toPx()
        val branchY = 28.dp.toPx().coerceAtMost(size.height / 2f)
        fun directedX(level: Int): Float {
            val ltrX = indentPx * level + indentPx / 2f
            return if (layoutDirection == LayoutDirection.Ltr) ltrX else size.width - ltrX
        }
        position.ancestorReplyIds.indices.forEach { level ->
            drawLine(
                color = color,
                start = Offset(directedX(level), 0f),
                end = Offset(directedX(level), size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        val parentLevel = position.depth.coerceAtLeast(0)
        val branchStartLevel = (parentLevel - 1).coerceAtLeast(0)
        val branchStartX = directedX(branchStartLevel)
        val branchEndX = if (layoutDirection == LayoutDirection.Ltr) size.width else 0f
        drawLine(
            color = color,
            start = Offset(branchStartX, 0f),
            end = Offset(branchStartX, branchY),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = color,
            start = Offset(branchStartX, branchY),
            end = Offset(branchEndX, branchY),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        if (position.hasDescendants) {
            val childX = directedX(parentLevel)
            drawLine(
                color = color,
                start = Offset(childX, branchY),
                end = Offset(childX, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}
