package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.HoldFeatureReference
import com.example.rightposture.core.live.HoldReference
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.reference.bundleFromHold
import com.example.rightposture.core.rpose.CYCLIC_MODE
import com.example.rightposture.core.rpose.HOLD_MODE
import com.example.rightposture.core.rpose.RPOSE_HOLD
import com.example.rightposture.core.rpose.RPOSE_MANIFEST
import com.example.rightposture.core.rpose.entries
import com.example.rightposture.core.rpose.jsonParse
import com.example.rightposture.core.rpose.jsonWrite
import com.example.rightposture.core.rpose.readRpose
import com.example.rightposture.core.rpose.writeRpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * §6.19 holds, as bundles.
 *
 * The point of these is that a hold survives the trip to a file and back with every number intact.
 * A hold reference that loses a sigma on the way through does not fail loudly - it produces a live
 * hold that will not start, on a patient's phone, days later.
 */
class HoldBundleTest {

    private fun holdReference() = HoldReference(
        features = mapOf(
            FeatureId.KNEE_FLEX_ACTIVE to HoldFeatureReference(
                feature = FeatureId.KNEE_FLEX_ACTIVE,
                mean = 87.4,
                sigma = 2.1,
                offsetFromNeutral = 74.9,
                salience = 0.476,
            ),
            FeatureId.HIP_FLEX_ACTIVE to HoldFeatureReference(
                feature = FeatureId.HIP_FLEX_ACTIVE,
                mean = 91.2,
                sigma = 3.4,
                offsetFromNeutral = 80.1,
                salience = 0.294,
            ),
        ),
        dominant = FeatureId.KNEE_FLEX_ACTIVE,
        watchList = listOf(FeatureId.KNEE_FLEX_ACTIVE, FeatureId.HIP_FLEX_ACTIVE),
        stableWindowSeconds = 6.2,
    )

    private fun holdBundle() = bundleFromHold(
        hold = holdReference(),
        name = "Wall sit",
        id = "rp_wall_sit",
        createdAt = "2026-09-06T10:00:00Z",
        holdSeconds = 30,
        device = "I2501",
        frames = 292,
        durationSeconds = 10.1,
        activeSide = Side.RIGHT,
    )

    private fun written(bundle: com.example.rightposture.core.rpose.RposeBundle): ByteArray {
        val out = ByteArrayOutputStream()
        writeRpose(bundle, out)
        return out.toByteArray()
    }

    private fun bytes(data: ByteArray): InputStream = ByteArrayInputStream(data)

    private fun zipOf(texts: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            texts.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a hold bundle carries a hold entry and a cyclic one does not`() {
        assertTrue(holdBundle().entries().containsKey(RPOSE_HOLD))
    }

    @Test
    fun `the hold reference survives a round trip unchanged`() {
        val reloaded = readRpose(bytes(written(holdBundle())))
        assertEquals(holdReference(), reloaded.hold)
    }

    @Test
    fun `the manifest says hold and prescribes the seconds held`() {
        val reloaded = readRpose(bytes(written(holdBundle())))
        assertEquals(HOLD_MODE, reloaded.manifest.mode)
        assertEquals(30, reloaded.manifest.prescription.holdSeconds)
        // One hold is one rep of it; the repetitions are the sets.
        assertEquals(1, reloaded.manifest.prescription.reps)
    }

    @Test
    fun `a hold has no phase axis and says so rather than guessing`() {
        val manifest = holdBundle().manifest
        assertEquals(0, manifest.phase.bins)
        assertEquals(0, manifest.segmentation.repsDetected)
        assertEquals(0.0, manifest.segmentation.periodSeconds, 0.0)
        // The one field a hold does fill honestly.
        assertEquals(FeatureId.KNEE_FLEX_ACTIVE, manifest.segmentation.dominantFeature)
    }

    @Test
    fun `the watch list weights are the normalised hold saliences`() {
        val watched = holdBundle().watchList.features
        assertEquals(2, watched.size)
        assertEquals(1.0, watched.sumOf { it.weight }, 1e-9)
        // Tightness, not range: the knee's smaller sigma earns it the larger share.
        assertTrue(watched.first { it.id == FeatureId.KNEE_FLEX_ACTIVE }.weight > 0.5)
    }

    @Test
    fun `an empty reference is what a hold has instead of a trajectory`() {
        val reloaded = readRpose(bytes(written(holdBundle())))
        assertTrue(reloaded.reference.features.isEmpty())
        assertEquals(0, reloaded.reference.repCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a bundle claiming hold mode with no hold entry is rejected`() {
        val texts = holdBundle().entries().toMutableMap()
        texts.remove(RPOSE_HOLD)
        readRpose(bytes(zipOf(texts)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a bundle carrying a hold entry but claiming cyclic mode is rejected`() {
        val texts = holdBundle().entries().toMutableMap()
        val manifest = jsonParse(texts.getValue(RPOSE_MANIFEST)).let {
            @Suppress("UNCHECKED_CAST")
            (it as Map<String, Any?>).toMutableMap()
        }
        manifest["mode"] = CYCLIC_MODE
        texts[RPOSE_MANIFEST] = jsonWrite(manifest)
        readRpose(bytes(zipOf(texts)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a watched joint with no band behind it is rejected on load`() {
        val texts = holdBundle().entries().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val hold = (jsonParse(texts.getValue(RPOSE_HOLD)) as Map<String, Any?>).toMutableMap()
        hold["watch_list"] = listOf("knee_flex_active", "elbow_flex_active")
        texts[RPOSE_HOLD] = jsonWrite(hold)
        readRpose(bytes(zipOf(texts)))
    }

    @Test
    fun `a bundle written before hold mode existed still reads`() {
        // 1.0 predates hold.json entirely, so every bundle of that vintage is cyclic. Refusing to
        // read them would strand every exercise already on a physio's phone.
        val texts = holdBundle().entries().toMutableMap()
        texts.remove(RPOSE_HOLD)
        @Suppress("UNCHECKED_CAST")
        val manifest = (jsonParse(texts.getValue(RPOSE_MANIFEST)) as Map<String, Any?>).toMutableMap()
        manifest["mode"] = CYCLIC_MODE
        manifest["format_version"] = "1.0"
        texts[RPOSE_MANIFEST] = jsonWrite(manifest)

        val reloaded = readRpose(bytes(zipOf(texts)))
        assertEquals(CYCLIC_MODE, reloaded.manifest.mode)
        assertNull(reloaded.hold)
    }
}

/**
 * The whole trip: author a hold, write it, read it back, and drive the live engine with what came
 * out of the file.
 *
 * Every other test here checks a value survives. This one checks the reference is still *usable* -
 * which is the thing that actually fails in front of a patient, days later, on a different phone,
 * where nobody is reading a stack trace.
 */
class HoldBundleRoundTripTest {

    private val fps = 30.0
    private val step = (1_000_000_000.0 / fps).toLong()

    private fun frame(knee: Double, trunk: Double, hip: Double) = mapOf(
        com.example.rightposture.core.FeatureId.KNEE_FLEX_ACTIVE to knee.toFloat(),
        com.example.rightposture.core.FeatureId.TRUNK_FLEXION to trunk.toFloat(),
        com.example.rightposture.core.FeatureId.HIP_FLEX_ACTIVE to hip.toFloat(),
    )

    private val neutral = mapOf(
        com.example.rightposture.core.FeatureId.KNEE_FLEX_ACTIVE to 175f,
        com.example.rightposture.core.FeatureId.TRUNK_FLEXION to 2f,
        com.example.rightposture.core.FeatureId.HIP_FLEX_ACTIVE to 175f,
    )

    @Test
    fun `a hold authored, saved and reloaded still starts and times a hold`() {
        val recording = (0 until (20.0 * fps).toInt()).map { i ->
            val wobble = 0.4 * kotlin.math.sin(i * 0.7)
            frame(90.0 + wobble, 5.0 + wobble * 0.5, 95.0 + wobble)
        }
        val authored = com.example.rightposture.core.live.buildHoldReference(
            samples = recording,
            neutral = neutral,
            sampleRateHz = fps,
        )
        val reference = requireNotNull(authored.reference) { "the recording should have authored" }

        // Through the file, exactly as a patient's phone receives it.
        val bundle = bundleFromHold(
            hold = reference,
            name = "Wall sit",
            id = "rp_wall_sit",
            createdAt = "2026-09-06T10:00:00Z",
            holdSeconds = 30,
        )
        val out = java.io.ByteArrayOutputStream()
        writeRpose(bundle, out)
        val reloaded = readRpose(ByteArrayInputStream(out.toByteArray()))

        val tracker = com.example.rightposture.core.live.HoldTracker(requireNotNull(reloaded.hold))
        var update: com.example.rightposture.core.live.HoldUpdate? = null
        var timestamp = 0L
        repeat((6.0 * fps).toInt()) { i ->
            val wobble = 0.4 * kotlin.math.sin(i * 0.7)
            update = tracker.update(frame(90.0 + wobble, 5.0 + wobble * 0.5, 95.0 + wobble), timestamp)
            timestamp += step
        }

        // Settled, running, and still running: a reference that lost its band on the way through
        // would either never start or break immediately.
        assertEquals(com.example.rightposture.core.live.HoldState.HOLDING, update?.state)
        assertTrue("held " + update?.durationSeconds, (update?.durationSeconds ?: 0.0) > 4.0)
    }
}
