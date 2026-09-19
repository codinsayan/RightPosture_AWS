package com.example.rightposture.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll


import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.rightposture.ui.components.PatientTabs
import com.example.rightposture.ui.components.Pill
import com.example.rightposture.ui.components.RoleTabBar
import com.example.rightposture.ui.components.RpCard
import com.example.rightposture.ui.components.RpTopBar
import com.example.rightposture.ui.components.SecondaryAction
import com.example.rightposture.ui.components.SectionHeader
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens

/**
 * §4.5's "Me" - in the minimal form the Today rewrite requires, not its finished design.
 *
 * Today used to end in a stack of unrelated buttons: enter an invite code, switch role. Those are
 * settings rather than steps in doing a set, and UI-SCREENS.md moves them here. This screen exists
 * now because they had to go somewhere the moment they left Today - dropping them would have
 * removed working features to make a screen prettier.
 *
 * What is deliberately not here yet: voice and language, account sign-in state, and the linked-
 * physio card as the mock draws it. Those arrive when 4.5 is built in its own right.
 */
@Composable
fun PatientMeScreen(
    linkedTo: String?,
    canLink: Boolean,
    appVersion: String,
    onEnterCode: () -> Unit,
    onSwitchRole: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenProgress: () -> Unit,
) {
    Scaffold(
        containerColor = Tokens.CanvasBg,
        bottomBar = {
            RoleTabBar(
                destinations = PatientTabs,
                selectedIndex = 2,
                onSelect = { index ->
                    when (index) {
                        0 -> onOpenToday()
                        1 -> onOpenProgress()
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
            RpTopBar(subtitle = "Clinical Form Track")

            Column(
                modifier = Modifier.padding(horizontal = Space.Gutter),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Text("Me", style = Type.HeadlineLg, color = Tokens.TextPrimary)

                SectionHeader(title = "Your physiotherapist")
                RpCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                linkedTo ?: "Not linked",
                                style = Type.TitleMd,
                                color = Tokens.TextPrimary,
                            )
                            Text(
                                if (linkedTo != null) {
                                    "Your prescriptions come from here."
                                } else {
                                    "You can train without one. Linking adds prescriptions to Today."
                                },
                                style = Type.BodySm,
                                color = Tokens.TextSecondary,
                            )
                        }
                        if (linkedTo != null) {
                            Pill("Linked", Tokens.SuccessInk, Tokens.SuccessWash)
                        }
                    }
                    if (canLink) {
                        SecondaryAction(
                            label = if (linkedTo == null) "Enter a code" else "Enter a new code",
                            onClick = onEnterCode,
                        )
                    }
                }

                SectionHeader(title = "This app")
                RpCard {
                    SecondaryAction(
                        label = "Switch to physiotherapist mode",
                        onClick = onSwitchRole,
                    )
                    Text(
                        "Switching signs you out and shows the clinician's app instead.",
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
    }
}
