package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.ActiveFault
import com.example.rightposture.core.live.CueArbiter
import com.example.rightposture.core.live.CueLevel
import com.example.rightposture.core.live.END_SET_CUE
import com.example.rightposture.core.live.MASK_RULES
import com.example.rightposture.core.live.MaskRule
import com.example.rightposture.core.live.RESET_CUE
import com.example.rightposture.core.live.assertCueSetup
import com.example.rightposture.core.rpose.FaultCondition
import com.example.rightposture.core.rpose.FaultDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueArbiterTest {

    private val second = 1_000_000_000L

    private fun fault(
        id: String,
        feature: FeatureId,
        priority: Double = 1.0,
        maskedBy: List<String> = emptyList(),
    ) = FaultDefinition(
        id = id,
        conditions = listOf(FaultCondition(feature, "+", 3.0)),
        phaseRange = 0 to 100,
        priority = priority,
        maskedBy = maskedBy,
        cueShort = id + " short",
        cueFull = id + " full",
        cueAlt = id + " alt",
        arrowJoint = "knee",
        arrowAxis = "reference_position",
        generatedBy = "llm",
        validated = true,
    )

    private fun active(definition: FaultDefinition, z: Double) =
        ActiveFault(definition, 0L, z, z)

    // -------------------------------------------------------------------------------------
    // §6.15.1 masking
    // -------------------------------------------------------------------------------------

    @Test
    fun `the five mask rules are exactly the ones the plan lists`() {
        assertEquals(
            listOf(
                MaskRule(FeatureId.PELVIC_OBLIQUITY, FeatureId.KNEE_FRONTAL_ACTIVE),
                MaskRule(FeatureId.ANKLE_DORSIFLEX_ACTIVE, FeatureId.TRUNK_FLEXION),
                MaskRule(FeatureId.LUMBAR_FLEXION, FeatureId.SHOULDER_FLEX_ACTIVE),
                MaskRule(FeatureId.SHOULDER_ELEVATION, FeatureId.ELBOW_FLEX_ACTIVE),
                MaskRule(FeatureId.TRUNK_LATERAL, FeatureId.HIP_ABDUCTION_ACTIVE),
            ),
            MASK_RULES,
        )
    }

    @Test
    fun `a masking fault suppresses its target even when the target is worse`() {
        val pelvis = fault("pelvis", FeatureId.PELVIC_OBLIQUITY)
        val knee = fault("knee_valgus", FeatureId.KNEE_FRONTAL_ACTIVE)
        // The knee is visibly the worse deviation. The hip is the thing to cue.
        val cue = CueArbiter().select(listOf(active(knee, 6.0), active(pelvis, 3.2)), rep = 1, timestampNs = 0)
        assertEquals("pelvis", cue!!.faultId)
    }

    @Test
    fun `the masked fault is cued normally when its masker is not active`() {
        val knee = fault("knee_valgus", FeatureId.KNEE_FRONTAL_ACTIVE)
        val cue = CueArbiter().select(listOf(active(knee, 6.0)), rep = 1, timestampNs = 0)
        assertEquals("knee_valgus", cue!!.faultId)
    }

    @Test
    fun `masking works the declared direction only`() {
        // The rule is trunk_lateral masks hip_abduction. The reverse must not suppress the lean.
        val lean = fault("trunk_lean", FeatureId.TRUNK_LATERAL)
        val abduction = fault("abduction", FeatureId.HIP_ABDUCTION_ACTIVE, priority = 5.0)
        val cue = CueArbiter().select(listOf(active(abduction, 4.0), active(lean, 3.1)), rep = 1, timestampNs = 0)
        assertEquals("trunk_lean", cue!!.faultId)
    }

    @Test
    fun `an explicit masked_by entry suppresses too`() {
        val cause = fault("cause", FeatureId.KNEE_FLEX_ACTIVE)
        val effect = fault("effect", FeatureId.HIP_FLEX_ACTIVE, priority = 9.0, maskedBy = listOf("cause"))
        val cue = CueArbiter().select(listOf(active(effect, 9.0), active(cause, 3.0)), rep = 1, timestampNs = 0)
        assertEquals("cause", cue!!.faultId)
    }

    // -------------------------------------------------------------------------------------
    // §6.15.2 ranking, and exactly one cue
    // -------------------------------------------------------------------------------------

    @Test
    fun `exactly one cue is selected from several active faults`() {
        val a = fault("a", FeatureId.KNEE_FLEX_ACTIVE, priority = 0.4)
        val b = fault("b", FeatureId.HIP_FLEX_ACTIVE, priority = 0.4)
        val c = fault("c", FeatureId.TRUNK_ROTATION, priority = 0.4)
        val cue = CueArbiter().select(
            listOf(active(a, 3.2), active(b, 5.0), active(c, 4.0)),
            rep = 1,
            timestampNs = 0,
        )
        assertEquals("b", cue!!.faultId)
    }

    @Test
    fun `priority outranks a larger deviation`() {
        val minor = fault("minor", FeatureId.KNEE_FLEX_ACTIVE, priority = 0.1)
        val important = fault("important", FeatureId.HIP_FLEX_ACTIVE, priority = 0.9)
        val cue = CueArbiter().select(
            listOf(active(minor, 6.0), active(important, 4.0)),
            rep = 1,
            timestampNs = 0,
        )
        assertEquals("important", cue!!.faultId)
    }

    @Test
    fun `nothing active means nothing said`() {
        assertNull(CueArbiter().select(emptyList(), rep = 1, timestampNs = 0))
    }

    // -------------------------------------------------------------------------------------
    // §6.15.3 cooldowns
    // -------------------------------------------------------------------------------------

    @Test
    fun `the same fault is not repeated inside five seconds`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE)
        assertEquals("knee", arbiter.select(listOf(active(knee, 4.0)), 1, 0)!!.faultId)
        assertNull(arbiter.select(listOf(active(knee, 4.0)), 2, 4 * second))
        assertEquals("knee", arbiter.select(listOf(active(knee, 4.0)), 3, 5 * second)!!.faultId)
    }

    @Test
    fun `nothing at all is said inside three seconds of the last utterance`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE)
        val trunk = fault("trunk", FeatureId.TRUNK_ROTATION)
        arbiter.select(listOf(active(knee, 4.0)), 1, 0)
        assertNull("global cooldown must silence a different fault too",
            arbiter.select(listOf(active(trunk, 5.0)), 1, 2 * second))
        assertEquals("trunk", arbiter.select(listOf(active(trunk, 5.0)), 1, 3 * second)!!.faultId)
    }

    @Test
    fun `a fault on its own cooldown does not silence a different one`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE, priority = 9.0)
        val trunk = fault("trunk", FeatureId.TRUNK_ROTATION, priority = 0.1)
        arbiter.select(listOf(active(knee, 4.0)), 1, 0)
        // Knee still outranks trunk, but knee is on its five-second cooldown.
        val cue = arbiter.select(listOf(active(knee, 9.0), active(trunk, 3.1)), 2, 4 * second)
        assertEquals("trunk", cue!!.faultId)
    }

    @Test
    fun `a frame blocked by the global cooldown does not consume an escalation step`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE)
        arbiter.select(listOf(active(knee, 4.0)), 1, 0)
        arbiter.select(listOf(active(knee, 4.0)), 1, 1 * second)
        arbiter.select(listOf(active(knee, 4.0)), 1, 2 * second)
        val next = arbiter.select(listOf(active(knee, 4.0)), 2, 6 * second)!!
        assertEquals(2, next.occurrence)
        assertEquals(CueLevel.FULL, next.level)
    }

    // -------------------------------------------------------------------------------------
    // §6.15.4 escalation
    // -------------------------------------------------------------------------------------

    @Test
    fun `the ladder runs short, full, reset, end set in that order`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE)
        val cues = (0..3).map { arbiter.select(listOf(active(knee, 4.0)), it + 1, it * 6L * second)!! }

        assertEquals(
            listOf(CueLevel.SHORT, CueLevel.FULL, CueLevel.RESET, CueLevel.END_SET),
            cues.map { it.level },
        )
        assertEquals("knee short", cues[0].text)
        assertEquals("knee full", cues[1].text)
        assertEquals(RESET_CUE, cues[2].text)
        assertEquals(END_SET_CUE, cues[3].text)
    }

    @Test
    fun `after ending the set the fault is never cued again`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE)
        repeat(4) { arbiter.select(listOf(active(knee, 4.0)), it + 1, it * 6L * second) }
        assertNull(arbiter.select(listOf(active(knee, 4.0)), 5, 60 * second))
    }

    @Test
    fun `a new set starts the ladder again`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE)
        repeat(2) { arbiter.select(listOf(active(knee, 4.0)), it + 1, it * 6L * second) }
        arbiter.resetSet()
        val first = arbiter.select(listOf(active(knee, 4.0)), 1, 100 * second)!!
        assertEquals(CueLevel.SHORT, first.level)
        assertEquals(1, first.occurrence)
    }

    @Test
    fun `each fault climbs its own ladder`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE, priority = 9.0)
        val trunk = fault("trunk", FeatureId.TRUNK_ROTATION, priority = 0.1)
        arbiter.select(listOf(active(knee, 4.0)), 1, 0)
        val trunkCue = arbiter.select(listOf(active(trunk, 4.0)), 2, 4 * second)!!
        assertEquals(CueLevel.SHORT, trunkCue.level)
        val kneeCue = arbiter.select(listOf(active(knee, 4.0)), 3, 9 * second)!!
        assertEquals(CueLevel.FULL, kneeCue.level)
    }

    // -------------------------------------------------------------------------------------
    // §6.15.5 cue effect
    // -------------------------------------------------------------------------------------

    @Test
    fun `every utterance is logged with the z that triggered it`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE)
        arbiter.select(listOf(active(knee, 4.2)), rep = 3, timestampNs = 7 * second)
        val event = arbiter.log.single()
        assertEquals("knee", event.faultId)
        assertEquals(3, event.rep)
        assertEquals(4.2, event.zAtFire, 1e-9)
        assertEquals(7 * second, event.firedAtNs)
        assertNull(event.resolved)
    }

    @Test
    fun `a cue that did not land is followed by the alternative wording, not by escalation`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE)
        val first = arbiter.select(listOf(active(knee, 4.0)), 1, 0)!!
        assertEquals("knee short", first.text)

        arbiter.recordFollowUp("knee", zAfterTwoSeconds = 4.4)
        val second = arbiter.select(listOf(active(knee, 4.4)), 2, 6 * this.second)!!
        assertEquals("knee alt", second.text)
        assertTrue(second.usedAlternative)
        assertEquals(CueLevel.SHORT, second.level)
        assertEquals(1, second.occurrence)
    }

    @Test
    fun `a cue that landed escalates normally on the next occurrence`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE)
        arbiter.select(listOf(active(knee, 4.0)), 1, 0)
        arbiter.recordFollowUp("knee", zAfterTwoSeconds = 2.0)
        val next = arbiter.select(listOf(active(knee, 3.5)), 2, 6 * second)!!
        assertEquals("knee full", next.text)
        assertFalse(next.usedAlternative)
    }

    @Test
    fun `a follow-up inside the clear band records the fault as resolved`() {
        val arbiter = CueArbiter()
        val knee = fault("knee", FeatureId.KNEE_FLEX_ACTIVE)
        arbiter.select(listOf(active(knee, 4.0)), 1, 0)
        arbiter.recordFollowUp("knee", zAfterTwoSeconds = 1.0, zNextRep = 0.8)
        val event = arbiter.log.single()
        assertEquals(true, event.resolved)
        assertEquals(0.8, event.zNextRep!!, 1e-9)
    }

    // -------------------------------------------------------------------------------------
    // §6.15.1 startup assertion
    // -------------------------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `a masked_by naming a fault that does not exist fails at startup`() {
        assertCueSetup(
            listOf(fault("effect", FeatureId.KNEE_FLEX_ACTIVE, maskedBy = listOf("typo_cause"))),
        )
    }

    @Test
    fun `a consistent fault set passes startup`() {
        assertCueSetup(
            listOf(
                fault("cause", FeatureId.PELVIC_OBLIQUITY),
                fault("effect", FeatureId.KNEE_FRONTAL_ACTIVE, maskedBy = listOf("cause")),
            ),
        )
    }
}
