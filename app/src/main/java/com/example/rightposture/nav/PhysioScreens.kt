package com.example.rightposture.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.sync.LinkedPatient
import com.example.rightposture.ui.components.EmptyState
import com.example.rightposture.ui.components.PhysioTabs
import com.example.rightposture.ui.components.Pill
import com.example.rightposture.ui.components.PrimaryAction
import com.example.rightposture.ui.components.RoleTabBar
import com.example.rightposture.ui.components.RpCard
import com.example.rightposture.ui.components.RpTopBar
import com.example.rightposture.ui.components.SecondaryAction
import com.example.rightposture.ui.components.SectionHeader
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens

/**
 * Two of the three tabs that took what §5.1 shed.
 *
 * Patients is drawn against its mock; §5.8's Me is the minimal form, as the patient's was. §5.4's
 * Library outgrew this file and lives in its own. None of them is new functionality - every control
 * here was already on the physio home screen, where it was burying the one thing that screen is
 * for.
 */

/**
 * §5.2's "Patients" - the roster, and the way to invite.
 *
 * Who is linked is a different question from who has done a set, and only this list answers it. A
 * physio who invited someone and has seen nothing back needs to tell "they have not started yet"
 * apart from "the code never worked", and the activity feed cannot say which.
 *
 * A row now opens §5.3's patient detail, which is what the roster is for: the list answers "who",
 * and the only useful next question is "how are they doing".
 */
@Composable
fun PhysioPatientsScreen(
    patients: List<LinkedPatient>,
    canInvite: Boolean,
    onInvite: () -> Unit,
    onOpenPatient: (LinkedPatient) -> Unit,
    onOpenActivity: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenMe: () -> Unit,
) {
    PhysioTabScaffold(
        selectedTab = 1,
        title = "Patients",
        subtitle = when {
            !canInvite -> "Linking needs an account and a reachable server"
            patients.isEmpty() -> "Nobody linked yet"
            patients.size == 1 -> "1 patient linked"
            else -> "${patients.size} patients linked"
        },
        onOpenActivity = onOpenActivity,
        onOpenPatients = {},
        onOpenLibrary = onOpenLibrary,
        onOpenMe = onOpenMe,
    ) {
        if (!canInvite) {
            RpCard(background = Tokens.SurfaceCardWarm) {
                Text("Not available on this build", style = Type.HeadlineSm, color = Tokens.TextSecondary)
                Text(
                    "Inviting a patient needs an account and a server to link through. Exercises " +
                        "you record on this phone still work, and so does testing them.",
                    style = Type.BodyMd,
                    color = Tokens.TextSecondary,
                )
            }
            return@PhysioTabScaffold
        }

        if (patients.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Person,
                message = "Nobody linked yet. Invite a patient, read them the six-character code, " +
                    "and everything you prescribe reaches them from then on.",
                actionLabel = "Invite a patient",
                onAction = onInvite,
            )
            return@PhysioTabScaffold
        }

        SectionHeader(title = "Linked", note = "${patients.size}")
        patients.forEach { patient ->
            Surface(
                onClick = { onOpenPatient(patient) },
                shape = Radius.Card,
                color = Tokens.SurfaceCard,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Open ${patient.name ?: patient.email}"
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Space.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            patient.name ?: patient.email,
                            style = Type.TitleMd,
                            color = Tokens.TextPrimary,
                        )
                        if (patient.name != null) {
                            Text(patient.email, style = Type.BodySm, color = Tokens.TextSecondary)
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Pill("Linked", Tokens.SuccessInk, Tokens.SuccessWash)
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Tokens.TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        PrimaryAction(
            label = "Invite a patient",
            onClick = onInvite,
            icon = Icons.Filled.Add,
        )
    }
}

/**
 * §5.8's "Me", in the minimal form the Activity rewrite requires.
 *
 * As with the patient's, this exists now because "switch role" had to go somewhere the moment it
 * left the home screen. Account, language and the rest arrive when §5.8 is built in its own right.
 */
@Composable
fun PhysioMeScreen(
    appVersion: String,
    onSwitchRole: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenPatients: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    PhysioTabScaffold(
        selectedTab = 3,
        title = "Me",
        subtitle = "Account and app",
        onOpenActivity = onOpenActivity,
        onOpenPatients = onOpenPatients,
        onOpenLibrary = onOpenLibrary,
        onOpenMe = {},
    ) {
        RpCard {
            SecondaryAction(label = "Switch to patient mode", onClick = onSwitchRole)
            Text(
                "Switching signs you out and shows the patient's app instead.",
                style = Type.BodySm,
                color = Tokens.TextSecondary,
            )
            Text(
                "RightPosture $appVersion · pose analysis runs on this phone",
                style = Type.LabelMonoSm,
                color = Tokens.TextMuted,
            )
        }
    }
}

/** The chrome all four physio tabs share: wordmark, title, gutter, and the role tab bar. */
@Composable
private fun PhysioTabScaffold(
    selectedTab: Int,
    title: String,
    subtitle: String,
    onOpenActivity: () -> Unit,
    onOpenPatients: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenMe: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = Tokens.CanvasBg,
        bottomBar = {
            RoleTabBar(
                destinations = PhysioTabs,
                selectedIndex = selectedTab,
                onSelect = { index ->
                    when (index) {
                        0 -> onOpenActivity()
                        1 -> onOpenPatients()
                        2 -> onOpenLibrary()
                        3 -> onOpenMe()
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            RpTopBar(subtitle = "Physio")
            Column(
                modifier = Modifier.padding(horizontal = Space.Gutter),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = Type.HeadlineLg, color = Tokens.TextPrimary)
                    Text(subtitle, style = Type.BodyMd, color = Tokens.TextSecondary)
                }
                content()
                Box(modifier = Modifier.height(Space.xs))
            }
        }
    }
}
