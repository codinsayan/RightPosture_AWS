package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.live.ArrowSelector
import com.example.rightposture.core.live.FeatureZ
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.reference.WatchList
import com.example.rightposture.core.reference.WatchedFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arrow's stability rules, written against the three ways picking `argmax |z|` per frame failed
 * on a phone: it landed on a leg during an arm raise, it strobed at the threshold, and it traded
 * itself between two comparable features.
 */
class ArrowSelectorTest {

    private val arm = FeatureId.SHOULDER_FLEX_ACTIVE
    private val leg = FeatureId.KNEE_FLEX_ACTIVE

    /** An arm exercise: the shoulder carries almost all the watch-list weight. */
    private fun armExercise() = WatchList(
        features = listOf(
            WatchedFeature(arm, salience = 9.0, weight = 0.8),
            WatchedFeature(leg, salience = 1.0, weight = 0.2),
        ),
        rejected = emptyMap(),
    )

    private fun z(vararg pairs: Pair<FeatureId, Double>) = pairs.associate { (id, value) ->
        id to FeatureZ(id, value, measured = 0.0, mu = 0.0, sigmaEff = 1.0)
    }

    private fun ns(seconds: Double) = (seconds * 1_000_000_000).toLong()

    private fun selector() = ArrowSelector(armExercise(), Side.RIGHT)

    /** Feeds the same z-scores for a stretch of time at 30 fps. */
    private fun ArrowSelector.hold(
        zScores: Map<FeatureId, FeatureZ>,
        seconds: Double,
        startNs: Long,
        midRep: Boolean = true,
    ): Pair<FeatureId?, Long> {
        var now = startNs
        var last: FeatureId? = null
        val step = ns(1.0 / 30.0)
        val end = startNs + ns(seconds)
        while (now <= end) {
            last = select(zScores, midRep = midRep, occluded = false, timestampNs = now)
            now += step
        }
        return last to now
    }

    // -------------------------------------------------------------------------------------

    @Test
    fun `the arrow follows the exercise, not the loudest joint`() {
        // The leg is further off-reference in raw z, but it is not what this exercise is about.
        // §6.15.2 ranks on |z| x weight for speech; the arrow uses the same ranking.
        val selector = selector()
        val (chosen, _) = selector.hold(
            z(arm to 4.0, leg to 6.0),
            seconds = 1.0,
            startNs = 0L,
        )
        assertEquals(arm, chosen)
    }

    @Test
    fun `nothing is chosen between reps`() {
        // Phase is pinned near the rep start, so every z is measured against a point the patient is
        // not trying to be at yet.
        val selector = selector()
        val (chosen, _) = selector.hold(
            z(arm to 8.0),
            seconds = 1.0,
            startNs = 0L,
            midRep = false,
        )
        assertNull(chosen)
    }

    @Test
    fun `an occluded patient gets no arrow`() {
        val selector = selector()
        val chosen = selector.select(
            z(arm to 8.0),
            midRep = true,
            occluded = true,
            timestampNs = ns(1.0),
        )
        assertNull(chosen)
    }

    @Test
    fun `a brief spike does not summon the arrow`() {
        val selector = selector()
        // Above Z_ON, but for less than the entry dwell.
        val chosen = selector.hold(
            z(arm to 5.0),
            seconds = Params.FAULT_ENTRY_DWELL_SECONDS / 2,
            startNs = 0L,
        ).first
        assertNull(chosen)
    }

    @Test
    fun `an error below Z_ON never summons the arrow, however long it lasts`() {
        val selector = selector()
        val chosen = selector.hold(z(arm to 2.0), seconds = 5.0, startNs = 0L).first
        assertNull(chosen)
    }

    @Test
    fun `the arrow holds through a dip below the entry threshold`() {
        val selector = selector()
        val (chosen, next) = selector.hold(z(arm to 5.0), seconds = 1.0, startNs = 0L)
        assertEquals(arm, chosen)

        // Between Z_OFF and Z_ON: no longer fault-sized, but not corrected either. Dropping here is
        // what made the arrow strobe.
        val (stillHeld, _) = selector.hold(z(arm to 2.0), seconds = 2.0, startNs = next)
        assertEquals(arm, stillHeld)
    }

    @Test
    fun `the arrow leaves once the patient is corrected, after the exit dwell`() {
        val selector = selector()
        val (_, afterEntry) = selector.hold(z(arm to 5.0), seconds = 1.0, startNs = 0L)

        // Still shown immediately after correcting: the exit dwell has not elapsed.
        val justAfter = selector.select(
            z(arm to 0.2),
            midRep = true,
            occluded = false,
            timestampNs = afterEntry,
        )
        assertEquals(arm, justAfter)

        val (gone, _) = selector.hold(z(arm to 0.2), seconds = 2.0, startNs = afterEntry)
        assertNull(gone)
    }

    @Test
    fun `a comparable rival does not steal the arrow`() {
        val selector = selector()
        val (_, next) = selector.hold(z(arm to 5.0), seconds = 1.0, startNs = 0L)

        // leg scores 6.0 x 0.2 = 1.2 against the arm's 4.0 x 0.8 = 3.2. Not close to the margin.
        val (stillArm, _) = selector.hold(z(arm to 4.0, leg to 6.0), seconds = 3.0, startNs = next)
        assertEquals(arm, stillArm)
    }

    @Test
    fun `a decisively worse joint does take the arrow, once it has held`() {
        val selector = selector()
        val (_, next) = selector.hold(z(arm to 4.0), seconds = 1.0, startNs = 0L)

        // leg 20.0 x 0.2 = 4.0 against arm 1.6 x 0.8 = 1.28: past the 1.5x margin, and above Z_ON.
        val switching = z(arm to 1.6, leg to 20.0)

        // Not immediately - the switch has its own dwell.
        val early = selector.select(switching, midRep = true, occluded = false, timestampNs = next)
        assertEquals(arm, early)

        val (switched, _) = selector.hold(switching, seconds = 1.0, startNs = next)
        assertEquals(leg, switched)
    }

    @Test
    fun `a feature with no drawable geometry is never chosen`() {
        // TRUNK_ROTATION is out of the image plane, so §6.16 cannot draw it however wrong it is.
        val watchList = WatchList(
            features = listOf(WatchedFeature(FeatureId.TRUNK_ROTATION, 9.0, 1.0)),
            rejected = emptyMap(),
        )
        val selector = ArrowSelector(watchList, Side.RIGHT)
        val (chosen, _) = selector.hold(
            z(FeatureId.TRUNK_ROTATION to 9.0),
            seconds = 3.0,
            startNs = 0L,
        )
        assertNull(chosen)
    }

    @Test
    fun `a feature outside the watch list is never chosen`() {
        val selector = selector()
        val (chosen, _) = selector.hold(
            z(FeatureId.ELBOW_FLEX_ACTIVE to 9.0),
            seconds = 3.0,
            startNs = 0L,
        )
        assertNull(chosen)
    }

    @Test
    fun `reset forgets the held feature`() {
        val selector = selector()
        val (chosen, next) = selector.hold(z(arm to 5.0), seconds = 1.0, startNs = 0L)
        assertEquals(arm, chosen)

        selector.reset()
        // Entry dwell has to be served again from scratch.
        val immediate = selector.select(
            z(arm to 5.0),
            midRep = true,
            occluded = false,
            timestampNs = next,
        )
        assertNull(immediate)
    }
}
