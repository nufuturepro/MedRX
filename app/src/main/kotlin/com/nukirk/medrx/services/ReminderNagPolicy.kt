package com.nukirk.medrx.services

/** What should happen when a reminder has been left unanswered. */
enum class NagDecision {
    /** Alert again after the snooze interval. */
    RE_ALERT,

    /** Stop alerting and close the dose out as a skip. */
    CONVERT_TO_SKIP,

    /** Stop alerting and postpone the dose to its next slot. */
    CONVERT_TO_DELAY,

    /** Stop alerting; the dose simply stays unlogged (missed). */
    STOP
}

/**
 * How long an unanswered reminder keeps re-alerting.
 *
 * Ignoring a notification must not be a free snooze. Each re-alert counts in
 * the same per-dose tally as a manual snooze, so when "convert repeated snoozes
 * to a skip" is on, the user's own limit and action decide the outcome. With
 * the setting off there is still a hard cap, after which the app stops making
 * noise and leaves the dose as missed — it never writes a skip the user did not
 * ask for.
 */
object ReminderNagPolicy {

    /** Re-alerts allowed when the snooze limit is switched off. */
    const val MAX_UNANSWERED_ALERTS = 3

    fun decide(
        alertCount: Int,
        limitEnabled: Boolean,
        limit: Int,
        action: SnoozeLimitAction = SnoozeLimitAction.SKIP
    ): NagDecision {
        if (limitEnabled) {
            return if (alertCount >= SnoozePolicy.clampLimit(limit)) {
                if (action == SnoozeLimitAction.DELAY) NagDecision.CONVERT_TO_DELAY
                else NagDecision.CONVERT_TO_SKIP
            } else {
                NagDecision.RE_ALERT
            }
        }
        return if (alertCount >= MAX_UNANSWERED_ALERTS) NagDecision.STOP
        else NagDecision.RE_ALERT
    }
}
