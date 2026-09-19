package com.example.rightposture

import com.example.rightposture.core.rpose.CYCLIC_MODE
import com.example.rightposture.core.rpose.HOLD_MODE
import com.example.rightposture.ui.prescriptionLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Today says a patient has to do.
 *
 * The failure this guards against is quiet: a hold stored as one rep of an unnamed duration reads
 * as "3 × 1", which a patient would reasonably perform as three repetitions of nothing.
 */
class PrescriptionLineTest {

    @Test
    fun `a cyclic exercise reads as sets by reps`() {
        assertEquals("3 × 12", prescriptionLine(CYCLIC_MODE, sets = 3, reps = 12, holdSeconds = null))
    }

    @Test
    fun `a hold reads as sets by seconds, never as its rep count`() {
        assertEquals("3 × 30s hold", prescriptionLine(HOLD_MODE, sets = 3, reps = 1, holdSeconds = 30))
    }

    @Test
    fun `a hold with no stored duration says hold rather than inventing a number`() {
        // Bundles written before hold_s was populated. Naming the kind without a made-up duration
        // beats printing a rep count that was never meant literally.
        assertEquals("2 × hold", prescriptionLine(HOLD_MODE, sets = 2, reps = 1, holdSeconds = null))
    }
}
