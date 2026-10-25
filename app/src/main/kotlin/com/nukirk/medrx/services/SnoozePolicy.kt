package com.nukirk.medrx.services

/** What a snooze tap should do once the running snooze count is known. */
enum class SnoozeDecision {
    /** Ask again after the configured snooze duration. */
    RE_ARM,

    /** Stop asking and record the dose as a deliberate skip. */
    CONVERT_TO_SKIP,

    /** Stop asking and record the dose as postponed to its next slot. */
    CONVERT_TO_DELAY
}

/** What a dose becomes once it runs out of snoozes. */
enum class SnoozeLimitAction {
    /** Recorded as a skip: the dose is not being taken. */
    SKIP,

    /** Recorded as delayed: the dose moves to the next scheduled slot. */
    DELAY,

    ;

    /** The decision this action produces at the limit. */
    val decision: SnoozeDecision
        get() = if (this == DELAY) SnoozeDecision.CONVERT_TO_DELAY else SnoozeDecision.CONVERT_TO_SKIP

    companion object {
        /**
         * Reads the stored preference, falling back to [SKIP] so an unknown or
         * missing value can never change what the app records.
         */
        fun fromPref(raw: String?): SnoozeLimitAction =
            entries.firstOrNull { it.name == raw } ?: SKIP
    }
}

/**
 * How a snooze length should be displayed: whole hours when the value divides
 * evenly into hours, minutes otherwise.
 */
data class SnoozeLengthLabel(
    val hours: Int,
    val minutes: Int,
    val useHours: Boolean
)

/**
 * Pure snooze rules. Kept free of Android and resource types so the behaviour
 * that decides whether a dose is snoozed again or turned into a skip is
 * unit-testable on the JVM; callers supply the persisted count and the user's
 * settings, and translate the label into strings.
 */
object SnoozePolicy {

    const val DEFAULT_SNOOZE_DURATION_MINUTES = 10
    const val DEFAULT_SNOOZE_LIMIT = 3

    /** Lengths offered without typing a number. */
    val DURATION_PRESETS = listOf(5, 10, 15, 30, 60, 120)

    /** Quick lengths on the full-screen alarm; anything else opens the picker. */
    val QUICK_DURATION_PRESETS = listOf(5, 10, 15, 30)

    /** Escalation thresholds offered in settings. */
    val LIMIT_PRESETS = listOf(2, 3, 4, 5)

    /** Bounds for a hand-typed snooze length (1 minute to 12 hours). */
    const val MIN_DURATION_MINUTES = 1
    const val MAX_DURATION_MINUTES = 720

    /**
     * Whether [snoozeCount] snoozes (the count *after* the tap being handled)
     * must stop re-arming and be recorded as a skip. Disabled means the
     * reminder always repeats, matching the app's previous behaviour.
     */
    fun decide(
        snoozeCount: Int,
        limitEnabled: Boolean,
        limit: Int,
        action: SnoozeLimitAction = SnoozeLimitAction.SKIP
    ): SnoozeDecision {
        if (!limitEnabled) return SnoozeDecision.RE_ARM
        return if (snoozeCount >= clampLimit(limit)) action.decision else SnoozeDecision.RE_ARM
    }

    /**
     * The reason recorded for a converted dose. Delayed doses are stored as
     * skips so a resolved dose stays resolved, and are told apart by reason.
     */
    fun outcomeReason(decision: SnoozeDecision): SkipReason = when (decision) {
        SnoozeDecision.CONVERT_TO_DELAY -> SkipReason.DELAYED
        else -> SkipReason.SNOOZED_AWAY
    }

    /** Clamps a configured limit into the range the picker can express. */
    fun clampLimit(limit: Int): Int = limit.coerceIn(LIMIT_PRESETS.first(), LIMIT_PRESETS.last())

    /** Clamps a hand-typed snooze length, defaulting on unusable input. */
    fun clampDuration(minutes: Int?): Int = (minutes ?: DEFAULT_SNOOZE_DURATION_MINUTES)
        .coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)

    /** Chooses an hours or minutes presentation for [minutes]. */
    fun label(minutes: Int): SnoozeLengthLabel {
        val safe = clampDuration(minutes)
        return if (safe % 60 == 0) {
            SnoozeLengthLabel(hours = safe / 60, minutes = safe, useHours = true)
        } else {
            SnoozeLengthLabel(hours = safe / 60, minutes = safe, useHours = false)
        }
    }

    /**
     * True when [minutes] is a value the preset rows can highlight; a custom
     * length otherwise.
     */
    fun isPreset(minutes: Int): Boolean = DURATION_PRESETS.contains(minutes)
}
