package com.nukirk.medrx.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderNagPolicyTest {

    @Test
    fun `without a snooze limit, a reminder re-alerts to the hard cap then stops`() {
        val decisions = (1..ReminderNagPolicy.MAX_UNANSWERED_ALERTS).map {
            ReminderNagPolicy.decide(it, limitEnabled = false, limit = 3)
        }

        assertEquals(
            listOf(NagDecision.RE_ALERT, NagDecision.RE_ALERT, NagDecision.STOP),
            decisions
        )
    }

    @Test
    fun `the hard cap never writes a skip the user did not choose`() {
        assertTrue(
            (1..10).none { count ->
                ReminderNagPolicy.decide(count, limitEnabled = false, limit = 3)
                    .let { it == NagDecision.CONVERT_TO_SKIP || it == NagDecision.CONVERT_TO_DELAY }
            }
        )
    }

    @Test
    fun `with a limit enabled the configured action applies at the limit`() {
        val skip = (1..2).map {
            ReminderNagPolicy.decide(it, limitEnabled = true, limit = 2, action = SnoozeLimitAction.SKIP)
        }
        val delay = (1..2).map {
            ReminderNagPolicy.decide(it, limitEnabled = true, limit = 2, action = SnoozeLimitAction.DELAY)
        }

        assertEquals(listOf(NagDecision.RE_ALERT, NagDecision.CONVERT_TO_SKIP), skip)
        assertEquals(listOf(NagDecision.RE_ALERT, NagDecision.CONVERT_TO_DELAY), delay)
    }

    @Test
    fun `a stored limit outside the preset range is clamped`() {
        assertEquals(
            NagDecision.RE_ALERT,
            ReminderNagPolicy.decide(4, limitEnabled = true, limit = 99)
        )
        assertEquals(
            NagDecision.CONVERT_TO_SKIP,
            ReminderNagPolicy.decide(5, limitEnabled = true, limit = 99)
        )
    }

    @Test
    fun `manual snoozes and ignored reminders share one tally`() {
        // A snooze tap counts 1, so the first unanswered follow-up is count 2.
        val second = ReminderNagPolicy.decide(2, limitEnabled = true, limit = 3)
        val third = ReminderNagPolicy.decide(3, limitEnabled = true, limit = 3)

        assertEquals(NagDecision.RE_ALERT, second)
        assertEquals(NagDecision.CONVERT_TO_SKIP, third)
    }

    @Test
    fun `quick alarm presets are valid snooze lengths`() {
        assertTrue(SnoozePolicy.QUICK_DURATION_PRESETS.isNotEmpty())
        assertTrue(
            SnoozePolicy.QUICK_DURATION_PRESETS.all {
                it in SnoozePolicy.MIN_DURATION_MINUTES..SnoozePolicy.MAX_DURATION_MINUTES
            }
        )
        assertTrue(SnoozePolicy.DURATION_PRESETS.containsAll(SnoozePolicy.QUICK_DURATION_PRESETS))
    }
}
