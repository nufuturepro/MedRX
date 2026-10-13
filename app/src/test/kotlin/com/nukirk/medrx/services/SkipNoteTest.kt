package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The card's skip detail line reopens a note for editing, so a skip can be
 * explained after the fact — and corrected when the first note was wrong.
 */
class SkipNoteTest {

    private val day = LocalDate.of(2026, 10, 12)

    private fun medicine(vararg skips: Pair<LocalDate, SkipRecord>) = MedData(
        id = 1,
        groupId = 10,
        type = ItemType.Medicine,
        title = "Sertraline",
        creationDate = day.minusDays(30),
        creationTime = LocalTime.of(8, 0),
        skipHistory = HashMap(skips.toMap())
    )

    private fun skip(reason: SkipReason = SkipReason.OTHER, note: String? = null) =
        SkipRecord(reason = reason, time = LocalTime.of(9, 30), note = note)

    @Test
    fun `a note can be added to a skip that had none`() {
        val original = medicine(day to skip())
        val edited = original.withSkipNote(day, "pharmacy was out")

        assertEquals("pharmacy was out", edited.skipHistory[day]?.note)
        // The recorded reason and time, and the dose's taken state, stay put.
        assertEquals(SkipReason.OTHER, edited.skipHistory[day]?.reason)
        assertEquals(LocalTime.of(9, 30), edited.skipHistory[day]?.time)
        assertEquals(original.takenHistory, edited.takenHistory)
        assertTrue(edited.takenHistory.isEmpty())
    }

    @Test
    fun `an existing note is replaced and trimmed`() {
        val edited = medicine(day to skip(note = "wrong note"))
            .withSkipNote(day, "  forgot the refill  ")

        assertEquals("forgot the refill", edited.skipHistory[day]?.note)
    }

    @Test
    fun `a blank note removes it without dropping the skip`() {
        val edited = medicine(day to skip(note = "typed by accident")).withSkipNote(day, "   ")

        assertNull(edited.skipHistory[day]?.note)
        assertTrue(edited.skipHistory.containsKey(day))
    }

    @Test
    fun `a null note removes it`() {
        val edited = medicine(day to skip(note = "typed by accident")).withSkipNote(day, null)

        assertNull(edited.skipHistory[day]?.note)
        assertTrue(edited.skipHistory.containsKey(day))
    }

    @Test
    fun `skips on other days keep their notes`() {
        val other = day.minusDays(1)
        val edited = medicine(day to skip(note = "a"), other to skip(note = "b"))
            .withSkipNote(day, "c")

        assertEquals("c", edited.skipHistory[day]?.note)
        assertEquals("b", edited.skipHistory[other]?.note)
    }

    @Test
    fun `a day with no skip is returned untouched`() {
        val item = medicine(day to skip(note = "a"))

        assertSame(item, item.withSkipNote(day.plusDays(1), "not a skipped day"))
    }

    @Test
    fun `re-saving the same note changes nothing`() {
        val item = medicine(day to skip(note = "same"))

        assertSame(item, item.withSkipNote(day, "  same  "))
    }
}
