package com.nukirk.medrx.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

/**
 * A skipped card has to explain itself: which reason was recorded and what the
 * user typed, without repeating what the status line above it already says.
 */
class SkipCardDetailTest {

    /** Stands in for the localized reason name, so the test can see which reason was asked for. */
    private val label: (SkipReason) -> String = { "label:${it.name}" }

    private fun record(reason: SkipReason, note: String? = null) = SkipRecord(
        reason = reason,
        time = LocalTime.NOON,
        note = note
    )

    @Test
    fun `reason and note are both shown`() {
        assertEquals(
            "label:DOCTOR_DIRECTED — forgot to refill",
            skipCardDetail(record(SkipReason.DOCTOR_DIRECTED, "forgot to refill"), label)
        )
    }

    @Test
    fun `a reason without a note is shown alone`() {
        assertEquals(
            "label:ACUTE_ILLNESS",
            skipCardDetail(record(SkipReason.ACUTE_ILLNESS), label)
        )
    }

    @Test
    fun `a blank note is treated as no note`() {
        assertEquals(
            "label:SUPPLY_MISSING",
            skipCardDetail(record(SkipReason.SUPPLY_MISSING, "   "), label)
        )
    }

    @Test
    fun `a postponed dose shows nothing because the status line already says it`() {
        assertNull(skipCardDetail(record(SkipReason.DELAYED), label))
    }

    @Test
    fun `a postponed dose that carries a note shows the note`() {
        assertEquals(
            "machine was booked out",
            skipCardDetail(record(SkipReason.DELAYED, "machine was booked out"), label)
        )
    }

    @Test
    fun `an automatic snooze-away skip still names its reason`() {
        assertEquals(
            "label:SNOOZED_AWAY",
            skipCardDetail(record(SkipReason.SNOOZED_AWAY), label)
        )
    }

    @Test
    fun `patient discretion is not special-cased away`() {
        assertEquals("label:OTHER", skipCardDetail(record(SkipReason.OTHER), label))
    }
}
