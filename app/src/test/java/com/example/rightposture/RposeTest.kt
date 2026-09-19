package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.BodyPosition
import com.example.rightposture.core.reference.CheckOutcome
import com.example.rightposture.core.reference.FeatureReference
import com.example.rightposture.core.reference.Reference
import com.example.rightposture.core.reference.ValidationResult
import com.example.rightposture.core.reference.ValidatorCheck
import com.example.rightposture.core.reference.WatchList
import com.example.rightposture.core.reference.WatchedFeature
import com.example.rightposture.core.rpose.FaultCondition
import com.example.rightposture.core.rpose.FaultDefinition
import com.example.rightposture.core.rpose.FaultSet
import com.example.rightposture.core.rpose.GoalMetrics
import com.example.rightposture.core.rpose.GoalWeights
import com.example.rightposture.core.rpose.Goals
import com.example.rightposture.core.rpose.RPOSE_FORMAT_VERSION
import com.example.rightposture.core.rpose.RPOSE_MANIFEST
import com.example.rightposture.core.rpose.RPOSE_REFERENCE
import com.example.rightposture.core.rpose.RposeBundle
import com.example.rightposture.core.rpose.RposeCapture
import com.example.rightposture.core.rpose.RposeManifest
import com.example.rightposture.core.rpose.RposePhase
import com.example.rightposture.core.rpose.RposePrescription
import com.example.rightposture.core.rpose.RposeSegmentation
import com.example.rightposture.core.rpose.entries
import com.example.rightposture.core.rpose.jsonParse
import com.example.rightposture.core.rpose.jsonWrite
import com.example.rightposture.core.rpose.nextVersion
import com.example.rightposture.core.rpose.readRpose
import com.example.rightposture.core.rpose.readRposeDemoVideo
import com.example.rightposture.core.rpose.writeRpose
import com.example.rightposture.core.rpose.writeRposeFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RposeTest {

    // -------------------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------------------

    private fun featureReference(offset: Float) = FeatureReference(
        mean = List(Params.PHASE_BINS) { offset + it * 0.5f },
        sigma = List(Params.PHASE_BINS) { 3f + it * 0.01f },
        derivative = List(Params.PHASE_BINS) { 0.5f },
        rom = 79.4,
        medianSigmaRaw = 3.6,
    )

    private fun bundle() = RposeBundle(
        manifest = RposeManifest(
            id = "rp_8f2a1c",
            version = 1,
            name = "Seated knee extension",
            createdBy = "physio_uid_123",
            createdAt = "2026-09-06T09:14:22Z",
            mode = "cyclic",
            referenceSource = "self",
            bodyPosition = "seated",
            laterality = "unilateral",
            activeSide = "right",
            requiredView = "sagittal",
            cameraFacing = "rear",
            mirrored = false,
            capture = RposeCapture(30, 1847, 61.6, "iQOO loaner"),
            segmentation = RposeSegmentation(9, 6, 4.2, FeatureId.KNEE_FLEX_ACTIVE),
            phase = RposePhase(101, 92.4, 171.8, 42 to 62, 0.62),
            prescription = RposePrescription(12, 3, 60, null),
        ),
        reference = Reference(
            features = mapOf(
                FeatureId.KNEE_FLEX_ACTIVE to featureReference(92.4f),
                FeatureId.TRUNK_FLEXION to featureReference(4.1f),
            ),
            repCount = 6,
            bodyPosition = BodyPosition.UPRIGHT,
        ),
        watchList = WatchList(
            features = listOf(
                WatchedFeature(FeatureId.KNEE_FLEX_ACTIVE, 9.2, 0.62),
                WatchedFeature(FeatureId.TRUNK_FLEXION, 5.4, 0.38),
            ),
            rejected = mapOf(FeatureId.LUMBAR_FLEXION to "ROM 4.0 below 8.0"),
        ),
        meanVisibility = mapOf(
            FeatureId.KNEE_FLEX_ACTIVE to 0.93f,
            FeatureId.TRUNK_FLEXION to 0.88f,
        ),
        faults = FaultSet(
            faults = listOf(
                FaultDefinition(
                    id = "knee_incomplete_extension",
                    conditions = listOf(FaultCondition(FeatureId.KNEE_FLEX_ACTIVE, "-", 3.0)),
                    phaseRange = 40 to 62,
                    priority = 1.0,
                    maskedBy = listOf("trunk_compensation"),
                    cueShort = "Straighten all the way.",
                    cueFull = "Push your heel forward until your leg is fully straight.",
                    cueAlt = "Reach your toes toward the far wall.",
                    arrowJoint = "ankle",
                    arrowAxis = "reference_position",
                    generatedBy = "llm",
                    validated = true,
                ),
            ),
        ),
        goals = Goals(
            baseline = GoalMetrics.EMPTY,
            goal = GoalMetrics(175.0, 85.0, 12),
            weights = GoalWeights(0.5, 0.3, 0.2),
            baselineCaptured = false,
        ),
        validation = ValidationResult(
            listOf(
                ValidatorCheck(1, "Surviving reps", CheckOutcome.PASS, "", "6 reps"),
                ValidatorCheck(
                    6,
                    "Camera view consistency",
                    CheckOutcome.NOT_EVALUATED,
                    "View stability was not measured. Re-record after camera calibration.",
                    "no classifier",
                ),
            ),
        ),
    )

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

    private fun written(bundle: RposeBundle, demo: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        writeRpose(bundle, out, demo)
        return out.toByteArray()
    }

    private fun bytes(data: ByteArray): InputStream = ByteArrayInputStream(data)

    // -------------------------------------------------------------------------------------
    // Round trip - the gate
    // -------------------------------------------------------------------------------------

    @Test
    fun `known-good bundle reloads without data loss`() {
        val original = bundle()
        val reloaded = readRpose(bytes(written(original)))
        assertEquals(original, reloaded)
    }

    @Test
    fun `round trip is stable across a second write`() {
        val once = readRpose(bytes(written(bundle())))
        val twice = readRpose(bytes(written(once)))
        assertEquals(once, twice)
    }

    @Test
    fun `bundle contains the five required entries`() {
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(bytes(written(bundle()))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
        }
        assertEquals(
            listOf(
                "manifest.json",
                "reference.json",
                "faults.json",
                "goals.json",
                "validation.json",
            ),
            names,
        )
    }

    // -------------------------------------------------------------------------------------
    // demo.mp4 is optional until captured
    // -------------------------------------------------------------------------------------

    @Test
    fun `bundle without a demo video is valid and reports no video`() {
        assertNull(readRposeDemoVideo(bytes(written(bundle()))))
    }

    @Test
    fun `demo video survives the round trip byte for byte`() {
        val demo = ByteArray(2048) { (it % 251).toByte() }
        val data = written(bundle(), demo)
        assertTrue(demo.contentEquals(readRposeDemoVideo(bytes(data))))
        assertEquals(bundle(), readRpose(bytes(data)))
    }

    // -------------------------------------------------------------------------------------
    // Version rules (§9.6)
    // -------------------------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `a bundle with no format_version is rejected`() {
        val texts = bundle().entries().toMutableMap()
        val manifest = jsonParse(texts.getValue(RPOSE_MANIFEST)) as Map<*, *>
        texts[RPOSE_MANIFEST] = jsonWrite(manifest.filterKeys { it != "format_version" })
        readRpose(bytes(zipOf(texts)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a bundle written by a future format version is rejected`() {
        val texts = bundle().entries().toMutableMap()
        // Off the constant rather than a literal, so bumping the format does not quietly turn this
        // into a test that rewrites nothing and then asserts a rejection that never happens.
        texts[RPOSE_MANIFEST] = texts.getValue(RPOSE_MANIFEST).replace(
            "\"format_version\":\"" + RPOSE_FORMAT_VERSION + "\"",
            "\"format_version\":\"2.0\"",
        )
        readRpose(bytes(zipOf(texts)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a bundle missing a required entry is rejected`() {
        readRpose(bytes(zipOf(bundle().entries() - "goals.json")))
    }

    @Test
    fun `a new version keeps the id and increments the version`() {
        val first = bundle().manifest
        val second = first.nextVersion()
        assertEquals(first.id, second.id)
        assertEquals(first.version + 1, second.version)
    }

    @Test
    fun `writing over an existing bundle is refused`() {
        val file = File.createTempFile("rp_immutable", ".rpose")
        // createTempFile leaves an empty file behind; that is exactly the collision being tested.
        val failure = runCatching { writeRposeFile(bundle(), file) }.exceptionOrNull()
        file.delete()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("immutable"))
    }

    @Test
    fun `writing to a fresh path succeeds and reloads`() {
        val directory = File.createTempFile("rp_dir", "").let { it.delete(); it.mkdirs(); it }
        val file = File(directory, "exercise_v1.rpose")
        writeRposeFile(bundle(), file)
        val reloaded = file.inputStream().use { readRpose(it) }
        assertEquals(bundle(), reloaded)
        file.delete()
        directory.delete()
    }

    // -------------------------------------------------------------------------------------
    // Array length (§6.8.4)
    // -------------------------------------------------------------------------------------

    @Test
    fun `a reference array of the wrong length is rejected on load`() {
        val texts = bundle().entries().toMutableMap()
        val shortened = jsonWrite(List(100) { 0.0 })
        val full = jsonWrite(
            bundle().reference.features.getValue(FeatureId.KNEE_FLEX_ACTIVE).mean.map { it.toDouble() },
        )
        // Replace one 101-long array with a 100-long one, leaving everything else intact.
        texts[RPOSE_REFERENCE] = texts.getValue(RPOSE_REFERENCE).replaceFirst(full, shortened)
        assertTrue(texts.getValue(RPOSE_REFERENCE).contains(shortened))

        val failure = runCatching { readRpose(bytes(zipOf(texts))) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("101 required"))
    }

    @Test
    fun `every stored array is 101 long`() {
        val reference = jsonParse(bundle().entries().getValue(RPOSE_REFERENCE)) as Map<*, *>
        val features = reference["features"] as Map<*, *>
        features.values.forEach { raw ->
            val feature = raw as Map<*, *>
            listOf("mu", "sigma", "dmu").forEach {
                assertEquals(Params.PHASE_BINS, (feature[it] as List<*>).size)
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // JSON codec
    // -------------------------------------------------------------------------------------

    @Test
    fun `strings needing escapes survive the round trip`() {
        val awkward = bundle().let {
            it.copy(manifest = it.manifest.copy(name = "Knee \"extension\"\n\tseated \\ 90°"))
        }
        assertEquals(awkward, readRpose(bytes(written(awkward))))
    }

    @Test
    fun `nulls are preserved rather than dropped`() {
        val reloaded = readRpose(bytes(written(bundle())))
        assertNull(reloaded.manifest.prescription.holdSeconds)
        assertNull(reloaded.goals.baseline.romPeak)
        assertEquals(GoalMetrics.EMPTY, reloaded.goals.baseline)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-finite number is refused rather than written as an unreadable token`() {
        jsonWrite(mapOf("sigma" to Double.NaN))
    }

    @Test
    fun `parser rejects trailing content`() {
        assertTrue(runCatching { jsonParse("{\"a\":1} junk") }.isFailure)
    }
}
