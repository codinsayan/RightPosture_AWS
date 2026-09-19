package com.example.rightposture.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens

/**
 * The wordmark bar every tabbed screen carries.
 *
 * [subtitle] names which app this is - "Clinical Form Track" for a patient, "Physio" for a
 * clinician - because §4.5 lets either role switch to the other, and after switching the only thing
 * distinguishing the two apps is what is on the screen.
 */
@Composable
fun RpTopBar(
    subtitle: String,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
    onNotifications: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.Gutter, vertical = Space.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(Tokens.SurfaceCardWarm, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = Tokens.PrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column {
                Text("RightPosture", style = Type.HeadlineSm, color = Tokens.Primary)
                Text(subtitle, style = Type.LabelMonoSm, color = Tokens.TextSecondary)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            badge?.invoke()
            if (onNotifications != null) {
                Surface(
                    onClick = onNotifications,
                    shape = CircleShape,
                    color = Tokens.SurfaceCard,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = "Notifications",
                            tint = Tokens.Primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One destination in the role tab bar. */
data class TabDestination(
    val label: String,
    val icon: ImageVector,
)

/**
 * The persistent per-role tab bar (UI-FLOWS.md §2).
 *
 * It is the app's only navigation chrome, and it is deliberately absent from three places: while a
 * set is being performed, while an exercise is being recorded, and during sign-in. Those are
 * focused tasks, and a tab bar during a set is an invitation to lose the set.
 */
@Composable
fun RoleTabBar(
    destinations: List<TabDestination>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Tokens.SurfaceCard)
            .height(Space.BottomNavHeight)
            .padding(horizontal = Space.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEachIndexed { index, destination ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                shape = Radius.Pill,
                color = if (selected) Tokens.PrimaryContainer else Tokens.SurfaceCard,
                modifier = Modifier.semantics {
                    this.selected = selected
                    contentDescription = destination.label
                },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = Space.md, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        destination.icon,
                        contentDescription = null,
                        tint = if (selected) Tokens.OnPrimary else Tokens.TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        destination.label,
                        style = Type.LabelMonoSm,
                        color = if (selected) Tokens.OnPrimary else Tokens.TextSecondary,
                    )
                }
            }
        }
    }
}

/** §4's three patient tabs. */
val PatientTabs = listOf(
    TabDestination("Today", Icons.Filled.DateRange),
    TabDestination("Progress", RpIcons.Monitoring),
    TabDestination("Me", Icons.Filled.Person),
)

/** §5's four physio tabs. */
val PhysioTabs = listOf(
    TabDestination("Activity", RpIcons.Monitoring),
    TabDestination("Patients", RpIcons.People),
    TabDestination("Library", RpIcons.Library),
    TabDestination("Me", Icons.Filled.Person),
)
