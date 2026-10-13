@file:OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)

package com.nukirk.medrx.elements.MainActivity.Tabs

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nukirk.medrx.ItemType
import com.nukirk.medrx.R
import com.nukirk.medrx.services.MedData
import com.nukirk.medrx.services.MedViewModel
import com.nukirk.medrx.services.isDelayed
import com.nukirk.medrx.services.isSkipped
import com.nukirk.medrx.ui.theme.GoogleSansFlex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class DayStatus {
    ALL_TAKEN, PARTIAL, NONE_TAKEN, NO_MEDS, FUTURE,

    /** Every scheduled dose accounted for, at least one via a recorded skip. */
    SKIPPED,

    /**
     * Every scheduled dose accounted for, at least one of them postponed to a
     * later dose. Nothing that day was actually missed, so the day must not be
     * painted like a missed one; it gets its own colour.
     */
    DELAYED
}

fun getScheduledMedsForDate(date: LocalDate, items: List<MedData>): List<MedData> {
    val scheduled = items.filter { item ->
        item.type == ItemType.Medicine && !item.isPrn &&
                !date.isBefore(item.creationDate) &&
                (item.endDate == null || !date.isAfter(item.endDate)) &&
                (item.recurrenceDays.isNullOrEmpty() || item.recurrenceDays.contains(date.dayOfWeek)) &&
                (item.intervalGap == null || ChronoUnit.DAYS.between(
                    item.creationDate,
                    date
                ) % item.intervalGap == 0L)
    }
    // Legacy edit bugs could leave several schedule fragments for the same
    // medication and time slot. Count each slot once per date — duplicates
    // would inflate the scheduled count and drag adherence down.
    return scheduled.distinctBy { it.medicationInstanceKey() }
}

fun getStatusForDate(date: LocalDate, items: List<MedData>): DayStatus {
    if (date.isAfter(LocalDate.now())) return DayStatus.FUTURE

    val scheduledMeds = getScheduledMedsForDate(date, items)
    if (scheduledMeds.isEmpty()) return DayStatus.NO_MEDS

    val takenCount = scheduledMeds.count { it.takenHistory.containsKey(date) }
    // A postponed dose removes itself from the day's outstanding work (it stops
    // reminding, and the next dose picks it up), so unlike before it *does*
    // resolve the day — but as its own delayed state, never as a skip, and it
    // still earns no adherence credit elsewhere.
    val skippedCount = scheduledMeds.count { it.isSkipped(date) }
    val delayedCount = scheduledMeds.count { it.isDelayed(date) }
    val accounted = takenCount + skippedCount + delayedCount

    return when {
        accounted == 0 -> DayStatus.NONE_TAKEN
        // Fully accounted: all taken, or a mix of taken + skipped / all skipped.
        takenCount == scheduledMeds.size -> DayStatus.ALL_TAKEN
        accounted == scheduledMeds.size ->
            if (delayedCount > 0) DayStatus.DELAYED else DayStatus.SKIPPED
        else -> DayStatus.PARTIAL
    }
}

/**
     * Number of recorded skips across all meds scheduled on [date]. Doses the
     * snooze limit postponed are excluded: they are counted as delayed, not as
     * skips, everywhere else too.
     */
    fun getSkipCountForDate(date: LocalDate, items: List<MedData>): Int =
        getScheduledMedsForDate(date, items).count { it.isSkipped(date) }

data class MedicationStats(
    val instanceKey: String,
    val treatmentKey: String,
    val title: String,
    val doseLabel: String,
    val scheduled: Int,
    val taken: Int,
    val skipped: Int,
    val delayed: Int,
    val missed: Int
) {
    /**
     * Doses the user actually acted on. A postponed dose is deliberately not
     * credited: it was moved, not taken, so allowing it to raise the score
     * would let repeated delaying hide behind a healthy percentage.
     */
    val adherence: Int
        get() = if (scheduled == 0) 0 else ((taken + skipped) * 100f / scheduled).toInt()
}

/** Calculates auditable per-instance stats; separate versions never collapse by title. */
fun getMedicationStatsForMonth(month: YearMonth, items: List<MedData>): List<MedicationStats> {
    val end = minOf(month.atEndOfMonth(), LocalDate.now())
    if (month.atDay(1).isAfter(end)) return emptyList()

    return getScheduledMedsForDate(month.atDay(1), items)
        .plus(items.filter { it.type == ItemType.Medicine })
        .distinctBy { it.medicationInstanceKey() }
        .map { seed ->
            var scheduled = 0
            var taken = 0
            var skipped = 0
            var delayed = 0
            var date = month.atDay(1)
            while (!date.isAfter(end)) {
                val slot = items.firstOrNull {
                    it.type == ItemType.Medicine && !it.isPrn &&
                        it.medicationInstanceKey() == seed.medicationInstanceKey() &&
                        !date.isBefore(it.creationDate) &&
                        (it.endDate == null || !date.isAfter(it.endDate)) &&
                        (it.recurrenceDays.isNullOrEmpty() || it.recurrenceDays.contains(date.dayOfWeek)) &&
                        (it.intervalGap == null || ChronoUnit.DAYS.between(it.creationDate, date) % it.intervalGap == 0L)
                }
                if (slot != null) {
                    scheduled++
                    if (slot.takenHistory.containsKey(date)) taken++
                    else if (slot.isDelayed(date)) delayed++
                    else if (slot.isSkipped(date)) skipped++
                }
                date = date.plusDays(1)
            }
            MedicationStats(
                instanceKey = seed.medicationInstanceKey(),
                treatmentKey = seed.treatmentKey(),
                title = seed.title,
                doseLabel = listOfNotNull(seed.doseAmount, seed.doseUnit).joinToString(" ").ifBlank { "—" },                    scheduled = scheduled,
                    taken = taken,
                    skipped = skipped,
                    delayed = delayed,
                    missed = scheduled - taken - skipped - delayed
                )
        }
        .filter { it.scheduled > 0 }
        .sortedWith(compareBy({ it.title }, { it.doseLabel }, { it.instanceKey }))
}

data class TreatmentStats(val treatmentKey: String, val title: String, val scheduled: Int, val accounted: Int) {
    val adherence: Int get() = if (scheduled == 0) 0 else (accounted * 100f / scheduled).toInt()
}

fun getCombinedTreatmentStats(stats: List<MedicationStats>): List<TreatmentStats> =
    stats.groupBy { it.treatmentKey to it.title }.map { (key, versions) ->
        TreatmentStats(key.first, key.second, versions.sumOf { it.scheduled }, versions.sumOf { it.taken + it.skipped })
    }.sortedBy { it.title }


data class PrnStats(val title: String, val doseLabel: String, val usesInMonth: Int, val totalUses: Int) {
    val key: String get() = title + "|" + doseLabel
}

fun getPrnStatsForMonth(month: java.time.YearMonth, items: List<MedData>): List<PrnStats> {
    val monthStart = month.atDay(1)
    val monthEnd = month.atEndOfMonth()
    return items.filter { it.type == ItemType.Medicine && it.isPrn }
        .groupBy { it.title to listOfNotNull(it.doseAmount, it.doseUnit).joinToString(" ").ifBlank { "-" } }
        .map { (key, meds) ->
            val (title, doseLabel) = key
            val usesInMonth = meds.sumOf { m -> m.prnUsages.count { it.date in monthStart..monthEnd } }
            val total = meds.sumOf { it.prnUsages.size }
            PrnStats(title, doseLabel, usesInMonth, total)
        }
        .filter { it.usesInMonth > 0 || it.totalUses > 0 }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.doseLabel }))
}

/** Headline numbers for the month shown in [StatsSummaryCard]. */
internal data class MonthlySummary(
    val streak: Int,
    val adherence: Int,
    val missedPerWeek: String,
    val delayed: Int
)

/**
 * The month's headline numbers, kept as a pure function of the data so the
 * arithmetic can be checked against the per-day statuses and the per-medication
 * buckets it summarises. [today] is a parameter rather than a clock reading so
 * the rollup is deterministic under test.
 *
 * Today is special: its doses only enter the totals once at least one has been
 * taken (an untouched morning must not drag the visible score down mid-day),
 * and while it is unaccounted it neither extends nor breaks a streak.
 */
internal fun computeMonthlySummary(
    month: YearMonth,
    items: List<MedData>,
    today: LocalDate
): MonthlySummary {
    val meds = items.filter { it.type == ItemType.Medicine }
    var streakCalc = 0
    var adherenceCalc = 0
    var missedPerWeekCalc = "0.0"
    var delayedCalc = 0

    if (meds.isNotEmpty()) {
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()

        if (!monthStart.isAfter(today)) {
            val evalEnd = if (monthEnd.isAfter(today)) today else monthEnd

            var totalScheduled = 0
            var totalTaken = 0
            var totalMissed = 0
            var totalDelayed = 0

            var currentStreak = 0
            var maxStreak = 0

            var dateCursor = monthStart
            while (!dateCursor.isAfter(evalEnd)) {
                val scheduled = getScheduledMedsForDate(dateCursor, meds)
                if (scheduled.isNotEmpty()) {
                    val takenCount = scheduled.count { it.takenHistory.containsKey(dateCursor) }
                    val skippedCount = scheduled.count { it.isSkipped(dateCursor) }
                    val delayedCount = scheduled.count { it.isDelayed(dateCursor) }
                    val scheduledCount = scheduled.size

                    val countForStats = !dateCursor.isEqual(today) || takenCount > 0

                    if (countForStats) {
                        totalScheduled += scheduledCount
                        totalTaken += takenCount + skippedCount
                        // Postponed doses are reported on their own line, so
                        // they stay out of "missed" here while still
                        // dragging adherence above.
                        totalMissed += (scheduledCount - takenCount - skippedCount - delayedCount)
                        totalDelayed += delayedCount
                    }

                    // A postponed dose must not extend a streak either.
                    if (takenCount + skippedCount == scheduledCount && scheduledCount > 0) {
                        currentStreak++
                        if (currentStreak > maxStreak) maxStreak = currentStreak
                    } else if (countForStats) {
                        currentStreak = 0
                    }
                }
                dateCursor = dateCursor.plusDays(1)
            }

            streakCalc = maxStreak
            adherenceCalc =
                if (totalScheduled > 0) (totalTaken * 100f / totalScheduled).toInt() else 0
            val totalDays =
                Math.max(1L, ChronoUnit.DAYS.between(monthStart, evalEnd.plusDays(1)))
            val weeks = Math.max(1f, totalDays / 7f)
            missedPerWeekCalc = String.format(Locale.US, "%.1f", totalMissed / weeks)
            delayedCalc = totalDelayed
        }
    }
    return MonthlySummary(streakCalc, adherenceCalc, missedPerWeekCalc, delayedCalc)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsTab(
    viewModel: MedViewModel,
    onNavigateToHome: (LocalDate) -> Unit
) {
    val initialPage = 1200
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 2400 })
    val currentMonth = remember(pagerState.currentPage) {
        YearMonth.now().plusMonths((pagerState.currentPage - initialPage).toLong())
    }

    val today = LocalDate.now()
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isExpanded =
        configuration.screenWidthDp > 600 || configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val allMeds = viewModel.items.filter { it.type == ItemType.Medicine }
    val medicationBreakdown = remember(currentMonth, allMeds, today) {
        getMedicationStatsForMonth(currentMonth, allMeds)
    }
    val prnStats = remember(currentMonth, allMeds) {
        getPrnStatsForMonth(currentMonth, allMeds)
    }
    val treatmentBreakdown = remember(medicationBreakdown) {
        getCombinedTreatmentStats(medicationBreakdown)
    }

    val stats = remember(currentMonth, allMeds, today) {
        computeMonthlySummary(currentMonth, allMeds, today)
    }

    val streak = stats.streak
    val adherence = stats.adherence
    val missedPerWeekStr = stats.missedPerWeek
    val delayedStr = stats.delayed.toString()

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    val onRefresh: () -> Unit = {
        isRefreshing = true
        scope.launch {
            delay(1000)
            viewModel.reloadData()
            isRefreshing = false
        }
    }

    PullToRefreshBox(
        state = pullRefreshState,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullRefreshState,
                    isRefreshing = isRefreshing
                )
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier.widthIn(max = 840.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (isExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                StatsSummaryCard(
                                    streak = streak.toString(),
                                    adherence = "$adherence%",
                                    missedPerWeek = missedPerWeekStr,
                                    delayed = delayedStr,
                                    isVertical = true
                                )
                            }
                            Box(modifier = Modifier.weight(1.5f)) {
                                CalendarCard(
                                    currentMonth = currentMonth,
                                    pagerState = pagerState,
                                    today = today,
                                    items = viewModel.items,
                                    onNavigateToHome = onNavigateToHome,
                                    initialPage = initialPage
                                )
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            StatsSummaryCard(
                                streak = streak.toString(),
                                adherence = "$adherence%",
                                missedPerWeek = missedPerWeekStr,
                                delayed = delayedStr,
                                isVertical = false
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            CalendarCard(
                                currentMonth = currentMonth,
                                pagerState = pagerState,
                                today = today,
                                items = viewModel.items,
                                onNavigateToHome = onNavigateToHome,
                                initialPage = initialPage
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(100.dp))
            }

            item {
                MedicationBreakdownCard(
                    instances = medicationBreakdown,
                    treatments = treatmentBreakdown
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                PrnUsageCard(stats = prnStats, month = currentMonth)
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}


@Composable
private fun PrnUsageCard(stats: List<PrnStats>, month: java.time.YearMonth) {
    if (stats.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(stringResource(R.string.prn_stats_title), style = MaterialTheme.typography.titleLarge, fontFamily = GoogleSansFlex, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.prn_stats_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.prn_stats_title), style = MaterialTheme.typography.titleLarge, fontFamily = GoogleSansFlex, fontWeight = FontWeight.Bold)
            stats.forEach { s ->
                Text(s.title + " · " + s.doseLabel + " — " + s.usesInMonth.toString() + " uses this month (" + s.totalUses.toString() + " total)", style = MaterialTheme.typography.bodyMedium, fontFamily = GoogleSansFlex, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MedicationBreakdownCard(
    instances: List<MedicationStats>,
    treatments: List<TreatmentStats>
) {
    if (instances.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.stats_medication_breakdown),
                style = MaterialTheme.typography.titleLarge,
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.Bold
            )
            treatments.forEach { treatment ->
                Text(
                    text = "${treatment.title} · ${treatment.adherence}% ${stringResource(R.string.stats_combined_label)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = GoogleSansFlex,
                    color = MaterialTheme.colorScheme.primary
                )
                instances.filter { it.treatmentKey == treatment.treatmentKey && it.title == treatment.title }.forEach { stats ->
                    Text(
                        text = "  ${stats.doseLabel}: ${stats.adherence}% · ${stats.taken} ${stringResource(R.string.stats_taken_short)}, ${stats.skipped} ${stringResource(R.string.stats_skipped_short)}, ${stats.delayed} ${stringResource(R.string.stats_delayed_short)}, ${stats.missed} ${stringResource(R.string.stats_missed_short)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = GoogleSansFlex,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** One tile of [StatsSummaryCard]; declared once so both layouts stay in sync. */
private data class StatTile(
    val icon: ImageVector,
    val value: String,
    val label: String,
    val iconColor: Color,
    val containerColor: Color,
    val backgroundRes: Int
)

@Composable
fun StatsSummaryCard(
    streak: String,
    adherence: String,
    missedPerWeek: String,
    delayed: String,
    isVertical: Boolean = false
) {
    // A postponed dose is deliberately not credited as on-time, but from the
    // calendar down it must not look like a miss either: the delayed tile (and
    // the matching calendar colour) is where that action shows up instead.
    val tiles = listOf(
        StatTile(
            icon = Icons.Rounded.LocalFireDepartment,
            value = streak,
            label = stringResource(R.string.stats_streak_label),
            iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            backgroundRes = R.drawable.ic_ghost
        ),
        StatTile(
            icon = Icons.Rounded.CheckCircle,
            value = adherence,
            label = stringResource(R.string.stats_on_time_label),
            iconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            backgroundRes = R.drawable.ic_twelve_sided_cookie
        ),
        StatTile(
            icon = Icons.Rounded.Schedule,
            value = delayed,
            label = stringResource(R.string.stats_delayed_label),
            iconColor = MaterialTheme.colorScheme.onTertiaryContainer,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            backgroundRes = R.drawable.ic_gem
        ),
        StatTile(
            icon = Icons.Rounded.Warning,
            value = missedPerWeek,
            label = stringResource(R.string.stats_missed_label),
            iconColor = MaterialTheme.colorScheme.onErrorContainer,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            backgroundRes = R.drawable.ic_pentagon
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        if (isVertical) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                tiles.forEach { tile ->
                    StatItem(
                        icon = tile.icon,
                        value = tile.value,
                        label = tile.label,
                        iconColor = tile.iconColor,
                        containerColor = tile.containerColor,
                        backgroundRes = tile.backgroundRes,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Equal widths keep the four tiles tidy on narrow phones, where
                // longer localised labels wrap instead of squeezing the row.
                tiles.forEach { tile ->
                    StatItem(
                        icon = tile.icon,
                        value = tile.value,
                        label = tile.label,
                        iconColor = tile.iconColor,
                        containerColor = tile.containerColor,
                        backgroundRes = tile.backgroundRes,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalendarCard(
    currentMonth: YearMonth,
    pagerState: PagerState,
    today: LocalDate,
    items: List<MedData>,
    onNavigateToHome: (LocalDate) -> Unit,
    initialPage: Int
) {
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CalendarHeader(
                currentMonth = currentMonth,
                onPreviousMonth = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                onNextMonth = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }
            )
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                val pageMonth = YearMonth.now().plusMonths((page - initialPage).toLong())
                CalendarGrid(
                    currentMonth = pageMonth,
                    today = today,
                    items = items,
                    onDateClick = onNavigateToHome,
                    getStatusForDate = { date -> getStatusForDate(date, items) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            CalendarLegend()
        }
    }
}

@Composable
fun StatItem(
    icon: ImageVector,
    value: String,
    label: String,
    iconColor: Color,
    containerColor: Color,
    backgroundRes: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = backgroundRes),
                contentDescription = null,
                tint = containerColor,
                modifier = Modifier.fillMaxSize()
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = GoogleSansFlex,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = GoogleSansFlex,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CalendarHeader(
    currentMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val monthName =
        currentMonth.month.getDisplayName(TextStyle.FULL, LocalLocale.current.platformLocale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(LocalLocale.current.platformLocale) else it.toString() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(
                Icons.Rounded.ChevronLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = "$monthName ${currentMonth.year}",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = GoogleSansFlex,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        IconButton(onClick = onNextMonth) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun CalendarGrid(
    currentMonth: YearMonth,
    today: LocalDate,
    items: List<MedData>,
    onDateClick: (LocalDate) -> Unit,
    getStatusForDate: (LocalDate) -> DayStatus
) {
    val daysOfWeek = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            daysOfWeek.forEach { dayOfWeek ->
                Text(
                    text = dayOfWeek.getDisplayName(
                        TextStyle.SHORT,
                        LocalLocale.current.platformLocale
                    ),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansFlex,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val firstDayOfMonth = currentMonth.atDay(1)
        val daysInMonth = currentMonth.lengthOfMonth()
        val firstDayOfWeekIndex = firstDayOfMonth.dayOfWeek.value - 1

        var currentDay = 1

        while (currentDay <= daysInMonth) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (i in 0..6) {
                    if (currentDay == 1 && i < firstDayOfWeekIndex) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    } else if (currentDay > daysInMonth) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    } else {
                        val date = currentMonth.atDay(currentDay)
                        val status = getStatusForDate(date)
                        val isToday = date == today

                        CalendarDayCell(
                            date = date,
                            status = status,
                            isToday = isToday,
                            skipCount = getSkipCountForDate(date, items),
                            modifier = Modifier.weight(1f),
                            onClick = { onDateClick(date) }
                        )
                        currentDay++
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarDayCell(
    date: LocalDate,
    status: DayStatus,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    skipCount: Int = 0,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = when (status) {
            DayStatus.ALL_TAKEN -> MaterialTheme.colorScheme.primary
            DayStatus.PARTIAL -> MaterialTheme.colorScheme.secondary
            DayStatus.NONE_TAKEN -> MaterialTheme.colorScheme.error
            DayStatus.SKIPPED -> MaterialTheme.colorScheme.tertiary
            // Postponed doses resolve the day, but as a softer state than both
            // a skip and a miss, so they get their own tinted colour.
            DayStatus.DELAYED -> MaterialTheme.colorScheme.tertiaryContainer
            DayStatus.NO_MEDS -> Color.Transparent
            DayStatus.FUTURE -> Color.Transparent
        },
        animationSpec = tween(300),
        label = "cell_color"
    )

    val textColor = when (status) {
        DayStatus.ALL_TAKEN -> MaterialTheme.colorScheme.onPrimary
        DayStatus.PARTIAL -> MaterialTheme.colorScheme.onSecondary
        DayStatus.NONE_TAKEN -> MaterialTheme.colorScheme.onError
        DayStatus.SKIPPED -> MaterialTheme.colorScheme.onTertiary
        DayStatus.DELAYED -> MaterialTheme.colorScheme.onTertiaryContainer
        DayStatus.FUTURE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cornerPercent by animateIntAsState(
        targetValue = if (isPressed) 15 else 50,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "corner"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(4.dp)
            .clip(RoundedCornerShape(cornerPercent))
            .background(containerColor)
            .then(
                if (isToday) Modifier.border(
                    3.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(cornerPercent)
                )
                else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = GoogleSansFlex,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = textColor
            )
            // Small dot(s) under the day number: one per skipped dose that day,
            // so a skipped day reads differently from a fully taken or missed one.
            if (skipCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(skipCount.coerceAtMost(3)) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(textColor.copy(alpha = 0.85f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarLegend() {
    // FlowRow rather than Row: five entries no longer fit on one line on a
    // narrow phone in the longer locales, so they wrap instead of clipping.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LegendItem(color = MaterialTheme.colorScheme.primary, label = stringResource(R.string.legend_all_taken))
        LegendItem(color = MaterialTheme.colorScheme.secondary, label = stringResource(R.string.legend_partial))
        LegendItem(color = MaterialTheme.colorScheme.tertiary, label = stringResource(R.string.legend_skipped))
        LegendItem(color = MaterialTheme.colorScheme.tertiaryContainer, label = stringResource(R.string.legend_delayed))
        LegendItem(color = MaterialTheme.colorScheme.error, label = stringResource(R.string.legend_missed))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = GoogleSansFlex,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}