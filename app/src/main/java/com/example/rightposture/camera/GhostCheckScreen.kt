package com.example.rightposture.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.core.live.GhostPoint
import com.example.rightposture.core.live.PlacedGhost
import com.example.rightposture.core.live.forCamera
import com.example.rightposture.core.live.medianBoneLengths
import com.example.rightposture.core.live.placeGhost
import com.example.rightposture.core.live.retargeted
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.classifyView
import com.example.rightposture.core.reference.AuthoringResult
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.SkeletonReference
import com.example.rightposture.core.reference.replayPhases
import com.example.rightposture.core.rpose.readRpose
import com.example.rightposture.data.ExerciseEntity
import com.example.rightposture.pose.POSE_SKELETON_CONNECTIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot

/** A ghost to check: its skeleton, which camera recorded it, and the view it needs. */
private data class GhostSource(
    val label: String,
    val skeleton: SkeletonReference?,
    val mirrored: Boolean,
    val view: CameraView?,
)

private val PHYSIO = Color(0xFF2E7D32)
private val GHOST = Color(0xCCFFFFFF)
private val BACKDROP = Color(0xFF1B1B1B)

/**
 * 10.5's sign check: the physio's own recording replayed with the ghost drawn over it.
 *
 * The placement fit is exactly determined and always "succeeds", so no test can say whether it
 * points the right way on a real phone. A person can, in two seconds: on their own recording the
 * ghost must sit on their own skeleton. This screen is where they say so, and the answer goes to the
 * device log under `RPGhost` with the camera and view it was given for.
 *
 * No camera image is kept, so both skeletons are drawn on a plain backdrop, letterboxed by the same
 * [letterboxOf] the live overlay uses.
 */
@Composable
internal fun GhostCheckScreen(
    result: AuthoringResult,
    frames: List<CapturedFrame>,
    /** Width / height of the analysed images, as the landmarks were measured on them. */
    imageAspect: Float,
    cameraFacing: String,
    /**
     * 10.6. Saved exercises whose ghost can be placed over this recording instead of its own - how a
     * reference recorded on one camera is checked against a body on the other.
     */
    saved: List<ExerciseEntity> = emptyList(),
    onDone: () -> Unit,
) {
    val liveMirrored = cameraFacing == "front"
    val own = GhostSource("This recording", result.skeleton, liveMirrored, result.recordedView)
    var others by remember { mutableStateOf<List<GhostSource>>(emptyList()) }
    LaunchedEffect(saved) {
        others = withContext(Dispatchers.IO) {
            saved.mapNotNull { entity ->
                val bundle = runCatching { File(entity.rposePath).inputStream().use { readRpose(it) } }.getOrNull()
                val skeleton = bundle?.skeleton ?: return@mapNotNull null
                GhostSource(
                    label = "${entity.name} v${entity.version} (${bundle.manifest.cameraFacing})",
                    skeleton = skeleton,
                    mirrored = bundle.manifest.mirrored,
                    view = CameraView.entries.firstOrNull { it.name.equals(bundle.manifest.requiredView, ignoreCase = true) },
                )
            }
        }
    }
    val sources = listOf(own) + others
    var chosen by remember { mutableIntStateOf(0) }
    val source = sources.getOrElse(chosen) { own }
    val skeleton = source.skeleton
    val requiredView = source.view

    val phases = remember(result) {
        val segmentation = result.segmentation
        if (segmentation == null) emptyList() else replayPhases(segmentation, result.dominantTrace)
    }
    // One placement per frame, computed once per source. The replay loops, and the verdict wants to
    // know how many frames the ghost could be placed on at all. The phase is always this
    // recording's own, so a saved exercise only lines up if it is the same movement.
    // 10.7. The body in this recording's own limb lengths, as a median over every frame: one frame's
    // lengths jitter, and a ghost whose forearm pulsed would be a worse picture than none.
    val limbs = remember(frames) { medianBoneLengths(frames.map { it.frame }) }
    val ghosts: List<PlacedGhost?> = remember(source, frames) {
        if (skeleton == null || requiredView == null) {
            emptyList()
        } else {
            frames.mapIndexed { index, captured ->
                val phase = phases.getOrNull(index) ?: return@mapIndexed null
                val pose = skeleton.poseAtPhase(phase).forCamera(source.mirrored, liveMirrored)
                placeGhost(pose, captured.frame, imageAspect, classifyView(captured.frame), requiredView)
                    ?.retargeted(pose, limbs)
            }
        }
    }
    val placed = ghosts.count { it != null }
    // 10.6. Chirality as a number, so a WRONG verdict says *why*. A ghost the right way round has
    // its wrists and elbows on the same-labelled live joints; a mirrored one on the opposite ones.
    val sides = remember(ghosts, frames) { sideDistances(ghosts, frames.map { it.frame }, imageAspect) }

    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(frames) {
        if (frames.size < 2) return@LaunchedEffect
        while (true) {
            val next = (index + 1) % frames.size
            val gapMs = ((frames[next].timestampNs - frames[index].timestampNs) / 1_000_000L)
                .coerceIn(1L, 200L)
            delay(gapMs)
            index = next
        }
    }

    fun verdict(matches: Boolean) {
        android.util.Log.i(
            "RPGhost",
            "sign-check verdict=%s source=\"%s\" sourceMirrored=%s camera=%s view=%s placed=%d/%d aspect=%.3f %s".format(
                if (matches) "MATCH" else "WRONG",
                source.label,
                source.mirrored,
                cameraFacing,
                requiredView?.name?.lowercase() ?: "none",
                placed,
                frames.size,
                imageAspect,
                sides,
            ),
        )
        onDone()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Check the ghost", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Green is you. White is the ghost built from this recording. They should move together, " +
                "limb on limb - the ghost's raised arm on your raised arm, not the other one.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (sources.size > 1) {
            Text("Ghost from", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sources.forEachIndexed { i, s ->
                    if (i == chosen) {
                        Button(onClick = {}) { Text(s.label) }
                    } else {
                        OutlinedButton(onClick = { chosen = i }) { Text(s.label) }
                    }
                }
            }
        }

        when {
            skeleton == null -> Text("This recording has no ghost reference.", color = MaterialTheme.colorScheme.error)
            requiredView == null -> Text(
                "The recording never settled on one camera view, so no ghost can be placed.",
                color = MaterialTheme.colorScheme.error,
            )
            placed == 0 -> Text(
                "The ghost could not be placed on any frame - the body's facing or view was never " +
                    "clear enough. That is a refusal, not a mismatch.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(BACKDROP)
                .semantics { contentDescription = "Recording with the ghost drawn over it" },
        ) {
            val frame = frames.getOrNull(index)?.frame ?: return@Canvas
            val box = letterboxOf(size, imageAspect)
            POSE_SKELETON_CONNECTIONS.forEach { (start, end) ->
                if (frame.visibility[start] >= 0.5f && frame.visibility[end] >= 0.5f) {
                    val a = frame.imageLandmarks[start]
                    val b = frame.imageLandmarks[end]
                    drawLine(PHYSIO, box.toCanvas(a.x, a.y), box.toCanvas(b.x, b.y), strokeWidth = 10f)
                }
            }
            ghosts.getOrNull(index)?.let { ghost ->
                POSE_SKELETON_CONNECTIONS.forEach { (start, end) ->
                    val a = ghost[start] ?: return@forEach
                    val b = ghost[end] ?: return@forEach
                    drawLine(GHOST, box.toCanvas(a.x, a.y), box.toCanvas(b.x, b.y), strokeWidth = 5f)
                }
            }
        }

        Text(
            "Ghost placed on $placed of ${frames.size} frames · $cameraFacing camera · " +
                (requiredView?.name?.lowercase() ?: "no view") +
                if (source.mirrored != liveMirrored) " · recorded on the other camera, relabelled" else "",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { verdict(false) }, modifier = Modifier.weight(1f)) {
                Text("Doesn't match")
            }
            Button(onClick = { verdict(true) }, modifier = Modifier.weight(1f), enabled = placed > 0) {
                Text("Matches")
            }
        }
    }
}

/**
 * Mean distance, in the live body's torso lengths, from the ghost's wrists and elbows to the live
 * joints of the **same** label, and to those of the **opposite** label. Right way round, `same` is
 * small and `cross` is about a body width; mirrored, the two swap.
 */
private fun sideDistances(ghosts: List<PlacedGhost?>, frames: List<LandmarkFrame>, aspect: Float): String {
    var same = 0.0
    var cross = 0.0
    var count = 0
    ghosts.forEachIndexed { index, ghost ->
        if (ghost == null) return@forEachIndexed
        val frame = frames[index]
        val p = frame.imageLandmarks
        val hipX = (p[PoseLandmark.LEFT_HIP].x + p[PoseLandmark.RIGHT_HIP].x) / 2f * aspect
        val hipY = (p[PoseLandmark.LEFT_HIP].y + p[PoseLandmark.RIGHT_HIP].y) / 2f
        val torso = hypot(
            (p[PoseLandmark.LEFT_SHOULDER].x + p[PoseLandmark.RIGHT_SHOULDER].x) / 2f * aspect - hipX,
            (p[PoseLandmark.LEFT_SHOULDER].y + p[PoseLandmark.RIGHT_SHOULDER].y) / 2f - hipY,
        )
        if (torso <= 0f) return@forEachIndexed
        SIDE_PAIRS.forEach { (left, right) ->
            if (frame.visibility[left] < 0.5f || frame.visibility[right] < 0.5f) return@forEach
            val gl = ghost[left] ?: return@forEach
            val gr = ghost[right] ?: return@forEach
            fun d(g: GhostPoint, id: Int) = hypot((g.x - p[id].x) * aspect, g.y - p[id].y) / torso
            same += (d(gl, left) + d(gr, right)) / 2.0
            cross += (d(gl, right) + d(gr, left)) / 2.0
            count++
        }
    }
    if (count == 0) return "sides=none"
    return "same=%.2f cross=%.2f".format(same / count, cross / count)
}

private val SIDE_PAIRS = listOf(
    PoseLandmark.LEFT_WRIST to PoseLandmark.RIGHT_WRIST,
    PoseLandmark.LEFT_ELBOW to PoseLandmark.RIGHT_ELBOW,
)
