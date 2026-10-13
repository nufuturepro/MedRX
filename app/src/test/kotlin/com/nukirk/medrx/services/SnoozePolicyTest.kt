package com.nukirk.medrx.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the snooze escalation rules: a dose must only be turned
 * into a skip when the user asked for a limit and actually reached it, so a
 * mis-set (or missing) preference can never silently drop a reminder.
 */
class SnoozePolicyTest {

    // --- decide ------------------------------------------------------------

    @Test
    fun `limit disabled always re-arms, however many snoozes`() {
        assertEquals(
            SnoozeDecision.RE_ARM,
            SnoozePolicy.decide(snoozeCount = 0, limitEnabled = false, limit = 3)
        )
        assertEquals(
            SnoozeDecision.RE_ARM,
            SnoozePolicy.decide(snoozeCount = 50, limitEnabled = false, limit = 3)
        )
    }

    @Test
    fun `snoozes below the limit re-arm`() {
        assertEquals(
            SnoozeDecision.RE_ARM,
            SnoozePolicy.decide(snoozeCount = 1, limitEnabled = true, limit = 3)
        )
        assertEquals(
            SnoozeDecision.RE_ARM,
            SnoozePolicy.decide(snoozeCount = 2, limitEnabled = true, limit = 3)
        )
    }

    @Test
    fun `reaching the limit converts to a skip`() {
        assertEquals(
            SnoozeDecision.CONVERT_TO_SKIP,
            SnoozePolicy.decide(snoozeCount = 3, limitEnabled = true, limit = 3)
        )
        assertEquals(
            SnoozeDecision.CONVERT_TO_SKIP,
            SnoozePolicy.decide(snoozeCount = 4, limitEnabled = true, limit = 3)
        )
    }

    @Test
    fun `the configured action decides what a converted dose becomes`() {
        assertEquals(
            SnoozeDecision.CONVERT_TO_DELAY,
            SnoozePolicy.decide(3, limitEnabled = true, limit = 3, action = SnoozeLimitAction.DELAY)
        )
        assertEquals(
            SnoozeDecision.RE_ARM,
            SnoozePolicy.decide(2, limitEnabled = true, limit = 3, action = SnoozeLimitAction.DELAY)
        )
        // A disabled limit must not convert, whatever the action is.
        assertEquals(
            SnoozeDecision.RE_ARM,
            SnoozePolicy.decide(99, limitEnabled = false, limit = 3, action = SnoozeLimitAction.DELAY)
        )
    }

    @Test
    fun `outcomes map to the reason that gets recorded`() {
        assertEquals(SkipReason.SNOOZED_AWAY, SnoozePolicy.outcomeReason(SnoozeDecision.CONVERT_TO_SKIP))
        assertEquals(SkipReason.DELAYED, SnoozePolicy.outcomeReason(SnoozeDecision.CONVERT_TO_DELAY))
        // Nothing was converted, so the default must stay the skip reason.
        assertEquals(SkipReason.SNOOZED_AWAY, SnoozePolicy.outcomeReason(SnoozeDecision.RE_ARM))
    }

    @Test
    fun `an unknown stored action falls back to skip`() {
        assertEquals(SnoozeLimitAction.DELAY, SnoozeLimitAction.fromPref(SnoozeLimitAction.DELAY.name))
        assertEquals(SnoozeLimitAction.SKIP, SnoozeLimitAction.fromPref(SnoozeLimitAction.SKIP.name))
        assertEquals(SnoozeLimitAction.SKIP, SnoozeLimitAction.fromPref(null))
        assertEquals(SnoozeLimitAction.SKIP, SnoozeLimitAction.fromPref("garbage"))
        // Default behaviour is unchanged for anyone who never opened the setting.
        assertEquals(SnoozeDecision.CONVERT_TO_SKIP, SnoozeLimitAction.SKIP.decision)
    }

    @Test
    fun `the limit is clamped to a sane range before it is applied`() {
        // A zero or negative limit must not convert on the very first snooze.
        assertEquals(SnoozePolicy.LIMIT_PRESETS.first(), SnoozePolicy.clampLimit(0))
        assertEquals(SnoozePolicy.LIMIT_PRESETS.first(), SnoozePolicy.clampLimit(-5))
        assertEquals(SnoozePolicy.LIMIT_PRESETS.last(), SnoozePolicy.clampLimit(99))

        assertEquals(
            SnoozeDecision.RE_ARM,
            SnoozePolicy.decide(snoozeCount = 1, limitEnabled = true, limit = 0)
        )
        assertEquals(
            SnoozeDecision.CONVERT_TO_SKIP,
            SnoozePolicy.decide(snoozeCount = 2, limitEnabled = true, limit = 0)
        )
    }

    // --- duration labels ---------------------------------------------------

    @Test
    fun `short snoozes read as minutes`() {
        val label = SnoozePolicy.label(15)
        assertFalse(label.useHours)
        assertEquals(15, label.minutes)
    }

    @Test
    fun `whole-hour snoozes read as hours`() {
        val hour = SnoozePolicy.label(60)
        assertTrue(hour.useHours)
        assertEquals(1, hour.hours)

        val twoHours = SnoozePolicy.label(120)
        assertTrue(twoHours.useHours)
        assertEquals(2, twoHours.hours)
    }

    @Test
    fun `a part-hour value stays in minutes`() {
        val label = SnoozePolicy.label(90)
        assertFalse(label.useHours)
        assertEquals(90, label.minutes)
    }

    @Test
    fun `duration is clamped, defaulting on unusable input`() {
        assertEquals(SnoozePolicy.DEFAULT_SNOOZE_DURATION_MINUTES, SnoozePolicy.clampDuration(null))
        assertEquals(SnoozePolicy.MIN_DURATION_MINUTES, SnoozePolicy.clampDuration(0))
        assertEquals(SnoozePolicy.MIN_DURATION_MINUTES, SnoozePolicy.clampDuration(-30))
        assertEquals(SnoozePolicy.MAX_DURATION_MINUTES, SnoozePolicy.clampDuration(9999))
        assertEquals(45, SnoozePolicy.clampDuration(45))
    }

    @Test
    fun `presets are recognised so custom values are shown separately`() {
        assertTrue(SnoozePolicy.isPreset(30))
        assertTrue(SnoozePolicy.isPreset(120))
        assertFalse(SnoozePolicy.isPreset(45))
        // The default must be selectable without typing a number.
        assertTrue(SnoozePolicy.isPreset(SnoozePolicy.DEFAULT_SNOOZE_DURATION_MINUTES))
    }

    // --- skip reason exposure ----------------------------------------------

    @Test
    fun `neither automatic reason is offered in the skip picker`() {
        val selectable = userSelectableSkipReasons
        assertFalse(selectable.contains(SkipReason.SNOOZED_AWAY))
        assertFalse(selectable.contains(SkipReason.DELAYED))
        assertTrue(SkipReason.SNOOZED_AWAY.isAutomatic)
        assertTrue(SkipReason.DELAYED.isAutomatic)
        assertFalse(SkipReason.OTHER.isAutomatic)
        // Everything the user can choose by hand is still offered.
        assertEquals(SkipReason.entries.size - 2, selectable.size)
        assertTrue(selectable.contains(SkipReason.OTHER))
        assertTrue(selectable.contains(SkipReason.ADVERSE_REACTION))
    }

    @Test
    fun `every skip reason has a doctor-report label`() {
        SkipReason.entries.forEach { reason ->
            assertTrue(
                "missing report label for $reason",
                SkipReport.skipReasonName(reason).isNotBlank()
            )
        }
    }
}
