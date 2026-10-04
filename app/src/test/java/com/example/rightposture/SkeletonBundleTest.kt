package com.example.rightposture

import com.example.rightposture.core.pose.syntheticTwoStepRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.SkeletonReference
import com.example.rightposture.core.reference.bundleFromAuthoring
import com.example.rightposture.core.reference.runAuthoringPipeline
import com.example.rightposture.core.rpose.RPOSE_FORMAT_VERSION
import com.example.rightposture.core.rpose.RPOSE_MANIFEST
import com.example.rightposture.core.rpose.RPOSE_SKELETON
import com.example.rightposture.core.rpose.RposeBundle
import com.example.rightposture.core.rpose.entries
import com.example.rightposture.core.rpose.jsonParse
import com.example.rightposture.core.rpose.jsonWrite
import com.example.rightposture.core.rpose.readRpose
import com.example.rightposture.core.rpose.writeRpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * 10.4. The ghost's reference in the `.rpose`, as an optional entry that no reader - old or new -
 * can be hurt by.
 */
class SkeletonBundleTest {

    // -------------------------------------------------------------------------------------
    // Round trip
    // -------------------------------------------------------------------------------------

    @Test
    fun `an authored bundle carries the skeleton as a sixth entry`() {
        val names = zipNames(written(authored()))

        assertEquals(
            listOf("manifest.json", "reference.json", "faults.json", "goals.json", "validation.json", RPOSE_SKELETON),
            names,
        )
    }

    @Test
    fun `the skeleton survives the trip through the file`() {
        val original = authored().skeleton!!
        val reloaded = readRpose(ByteArrayInputStream(written(authored()))).skeleton

        assertNotNull("the skeleton must come back", reloaded)
        assertSameSkeleton(original, reloaded!!)
    }

    @Test
    fun `a second trip changes nothing`() {
        // Quantisation happens once. Rounding already-rounded values must be exact, or every
        // re-save of an exercise would drift it.
        val once = readRpose(ByteArrayInputStream(written(authored())))
        val twice = readRpose(ByteArrayInputStream(written(once)))

        assertEquals(once.skeleton, twice.skeleton)
    }

    @Test
    fun `the entry stays small enough to parse on a phone`() {
        val text = authored().entries().getValue(RPOSE_SKELETON)

        assertTrue("skeleton.json is ${text.length / 1024} KB", text.length < 120 * 1024)
    }

    // -------------------------------------------------------------------------------------
    // Optional in both directions
    // -------------------------------------------------------------------------------------

    @Test
    fun `a bundle with no skeleton entry still reads, and is scored but not demonstrated`() {
        val texts = authored().entries().toMutableMap()
        texts.remove(RPOSE_SKELETON)

        val reloaded = readRpose(ByteArrayInputStream(zipOf(texts)))

        assertNull(reloaded.skeleton)
        assertTrue(reloaded.reference.features.isNotEmpty())
    }

    @Test
    fun `a 1_0 bundle with no skeleton still reads`() {
        val texts = authored().entries().toMutableMap()
        texts.remove(RPOSE_SKELETON)
        @Suppress("UNCHECKED_CAST")
        val manifest = (jsonParse(texts.getValue(RPOSE_MANIFEST)) as Map<String, Any?>).toMutableMap()
        manifest["format_version"] = "1.0"
        texts[RPOSE_MANIFEST] = jsonWrite(manifest)

        assertNull(readRpose(ByteArrayInputStream(zipOf(texts))).skeleton)
    }

    @Test
    fun `adding the skeleton changes nothing an older reader looks at`() {
        // Why there is no format bump. A build that predates this entry reads the five required
        // entries by name and ignores the rest, so it must find them byte-identical and the version
        // one it accepts. A bump would make it refuse every newly authored exercise instead.
        val bundle = authored()
        val with = bundle.entries()
        val without = bundle.copy(skeleton = null).entries()

        assertEquals("1.1", RPOSE_FORMAT_VERSION)
        assertEquals(without, with - RPOSE_SKELETON)
    }

    // -------------------------------------------------------------------------------------
    // Refused on load, naming the file
    // -------------------------------------------------------------------------------------

    @Test
    fun `a truncated positions array is refused`() {
        assertRefused { it["positions_torso"] = (it["positions_torso"] as List<*>).drop(3) }
    }

    @Test
    fun `a mis-strided spread array is refused`() {
        assertRefused { it["spread_torso"] = (it["spread_torso"] as List<*>) + 0.0 }
    }

    @Test
    fun `a skeleton on the wrong phase grid is refused`() {
        assertRefused { it["bins"] = 100.0 }
    }

    @Test
    fun `a phase profile that runs backwards is refused`() {
        // It would play the movement in reverse mid-rep. The file is the last place to catch it.
        assertRefused {
            val phase = (it["phase_by_time"] as List<*>).map { value -> value as Double }.toMutableList()
            phase[40] = phase[60] + 5.0
            it["phase_by_time"] = phase
        }
    }

    @Test
    fun `a zero rep duration is refused`() {
        assertRefused { it["rep_duration_s"] = 0.0 }
    }

    @Test
    fun `another build's landmark set is refused rather than misread`() {
        assertRefused { it["landmarks"] = (it["landmarks"] as List<*>).reversed() }
    }

    @Test
    fun `a non-boolean freshness flag is refused`() {
        assertRefused {
            val fresh = (it["fresh"] as List<*>).toMutableList()
            fresh[0] = 1.0
            it["fresh"] = fresh
        }
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private fun authored(): RposeBundle {
        val captured = syntheticTwoStepRepetitions(repCount = 8).map { CapturedFrame(it.frame, it.timestampNs) }
        val result = runAuthoringPipeline(captured)
        assertNull(result.failure, result.failure)
        return bundleFromAuthoring(
            result = result,
            name = "Two step",
            id = "rp_two_step",
            createdAt = "2026-09-29T00:00:00Z",
            prescribedReps = 12,
        )!!.also { assertNotNull("authoring must produce a skeleton", it.skeleton) }
    }

    private fun written(bundle: RposeBundle): ByteArray =
        ByteArrayOutputStream().also { writeRpose(bundle, it) }.toByteArray()

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

    private fun zipNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        return names
    }

    /** Edits `skeleton.json` and asserts the bundle is refused with a message naming that file. */
    private fun assertRefused(edit: (MutableMap<String, Any?>) -> Unit) {
        val texts = authored().entries().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val json = (jsonParse(texts.getValue(RPOSE_SKELETON)) as Map<String, Any?>).toMutableMap()
        edit(json)
        texts[RPOSE_SKELETON] = jsonWrite(json)
        try {
            readRpose(ByteArrayInputStream(zipOf(texts)))
            fail("a damaged skeleton.json must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue("the refusal must name the file: ${e.message}", e.message.orEmpty().contains(RPOSE_SKELETON))
        }
    }

    /** Equal to four decimals, which is what the file keeps. */
    private fun assertSameSkeleton(expected: SkeletonReference, actual: SkeletonReference) {
        assertEquals(expected.landmarks, actual.landmarks)
        assertEquals(expected.fresh, actual.fresh)
        assertEquals(expected.repCount, actual.repCount)
        fun close(name: String, a: List<Float>, b: List<Float>) {
            assertEquals(name, a.size, b.size)
            a.zip(b).forEachIndexed { index, (x, y) ->
                assertTrue("$name[$index]: $x vs $y", abs(x - y) <= 6e-5f)
            }
        }
        close("positions", expected.positions, actual.positions)
        close("spread", expected.spread, actual.spread)
        close("visibility", expected.visibility, actual.visibility)
        close("phaseByTime", expected.timing.phaseByTime, actual.timing.phaseByTime)
        assertEquals(expected.timing.repDurationSeconds, actual.timing.repDurationSeconds, 1e-4)
    }
}
