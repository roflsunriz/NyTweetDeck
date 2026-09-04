package dev.nytweetdeck.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaViewerDialogTest {
    @Test
    fun changesPageOnlyAfterTheAdjacentBoundaryCrossesHalfTheViewport() {
        assertEquals(0, photoPageDirectionForDrag(-499f, 1_000f))
        assertEquals(0, photoPageDirectionForDrag(-500f, 1_000f))
        assertEquals(1, photoPageDirectionForDrag(-501f, 1_000f))
        assertEquals(0, photoPageDirectionForDrag(500f, 1_000f))
        assertEquals(-1, photoPageDirectionForDrag(501f, 1_000f))
        assertEquals(0, photoPageDirectionForDrag(-1_000f, 0f))
    }

    @Test
    fun wrapsAdjacentPhotoIndexesAtBothEnds() {
        assertEquals(1, wrappedPhotoIndex(0, 2, 1))
        assertEquals(0, wrappedPhotoIndex(1, 2, 1))
        assertEquals(1, wrappedPhotoIndex(0, 2, -1))
        assertEquals(0, wrappedPhotoIndex(0, 0, 1))
    }
}
