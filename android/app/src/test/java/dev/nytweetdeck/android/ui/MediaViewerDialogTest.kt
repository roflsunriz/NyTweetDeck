package dev.nytweetdeck.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun adjacentPhotoIndexesStopAtBothEnds() {
        for (count in listOf(1, 2, 4)) {
            assertNull(adjacentPhotoIndex(0, count, -1))
            assertNull(adjacentPhotoIndex(count - 1, count, 1))
            for (index in 0 until count - 1) {
                assertEquals(index + 1, adjacentPhotoIndex(index, count, 1))
                assertEquals(index, adjacentPhotoIndex(index + 1, count, -1))
            }
        }
        assertNull(adjacentPhotoIndex(0, 0, 1))
    }
}
