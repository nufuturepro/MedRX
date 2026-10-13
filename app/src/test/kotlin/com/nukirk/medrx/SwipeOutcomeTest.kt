package com.nukirk.medrx

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the swipe policy behind [SwipeableSquishItem]: right past half the card
 * width asks to skip (only when the card offers it), left past half asks to
 * delete, anything shorter is a no-op. The real gesture only differs in that
 * the offset is clamped, so these thresholds are what decide intent.
 */
class SwipeOutcomeTest {

    private val width = 1000f

    private fun resolve(offsetX: Float, skipEnabled: Boolean = true) =
        resolveSwipeOutcome(offsetX, width, skipEnabled)

    @Test
    fun `right swipe past half asks to skip`() {
        assertEquals(SwipeOutcome.SKIP, resolve(600f))
        assertEquals(SwipeOutcome.SKIP, resolve(width * 0.75f))
    }

    @Test
    fun `left swipe past half asks to delete`() {
        assertEquals(SwipeOutcome.DELETE, resolve(-600f))
        assertEquals(SwipeOutcome.DELETE, resolve(-width))
    }

    @Test
    fun `short swipes in either direction do nothing`() {
        assertEquals(SwipeOutcome.NONE, resolve(0f))
        assertEquals(SwipeOutcome.NONE, resolve(499f))
        assertEquals(SwipeOutcome.NONE, resolve(-499f))
    }

    @Test
    fun `exactly half the width counts in the swiped direction`() {
        assertEquals(SwipeOutcome.SKIP, resolve(width / 2f))
        assertEquals(SwipeOutcome.DELETE, resolve(-width / 2f))
    }

    @Test
    fun `right swipe does nothing when the card has no skip to offer`() {
        // An event, an illness, or a dose that is already taken: a right swipe
        // must not resolve to a skip the caller cannot perform.
        assertEquals(SwipeOutcome.NONE, resolve(800f, skipEnabled = false))
        // Deleting is still available to every card, and only by swiping left.
        assertEquals(SwipeOutcome.DELETE, resolve(-800f, skipEnabled = false))
    }

    @Test
    fun `skip never wins over a delete on a left swipe`() {
        // Guards the branch order: a negative offset is a delete regardless of
        // whether skipping is enabled.
        assertEquals(SwipeOutcome.DELETE, resolve(-0.9f * width, skipEnabled = true))
    }

    @Test
    fun `unmeasured or empty card is inert`() {
        assertEquals(SwipeOutcome.NONE, resolveSwipeOutcome(900f, 0f, skipEnabled = true))
        assertEquals(SwipeOutcome.NONE, resolveSwipeOutcome(-900f, 0f, skipEnabled = true))
    }
}
