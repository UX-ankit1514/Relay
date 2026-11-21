package app.relay.ui.landing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.relay.design.AccentCard
import app.relay.design.CategoryLabel
import app.relay.design.HairlineDivider
import app.relay.design.NeutralCard
import app.relay.design.Page
import app.relay.design.PillAppearance
import app.relay.design.PillButton
import app.relay.design.RText
import app.relay.design.RelayColors
import app.relay.design.RelayTheme
import app.relay.design.Space
import app.relay.design.TextLink
import app.relay.nav.Route
import app.relay.ui.LocalServices

/** F1-01 · Landing — "Freelance projects that never lose their place." */
@Composable
fun LandingScreen() {
    val router = LocalServices.current.router
    val start = { router.navigate(Route.SignIn("/welcome")) }
    Page {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val narrow = maxWidth < 900.dp
            val gutter = if (narrow) Space.s16 else Space.s120
            Column(
                Modifier.widthIn(max = 1440.dp).fillMaxWidth().padding(horizontal = gutter).padding(bottom = Space.s80),
                verticalArrangement = Arrangement.spacedBy(Space.s80),
            ) {
                // Steep/Navigation/Header
                Row(Modifier.fillMaxWidth().padding(vertical = Space.s20), verticalAlignment = Alignment.CenterVertically) {
                    RText("Relay", RelayTheme.type.headingSmall)
                    Box(Modifier.weight(1f)) {
                        if (!narrow) Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(Space.s28)) {
                            TextLink("How it works", {})
                            TextLink("Why Relay", {})
                            TextLink("Trust & privacy", {})
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s32), verticalAlignment = Alignment.CenterVertically) {
                        TextLink("Sign in", { router.navigate(Route.SignIn()) })
                        PillButton("Start a project", start)
                    }
                }
                // Steep/Sections/Editorial header
                Column(Modifier.padding(vertical = Space.s40), verticalArrangement = Arrangement.spacedBy(Space.s24)) {
                    RText("Freelance projects that never\nlose their place.", if (narrow) RelayTheme.type.heading else RelayTheme.type.headingLarge)
                    RText("Agreements, decisions and approved work in one place — so anyone can pick up where someone left off.", RelayTheme.type.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                        PillButton("Start a project", start)
                        PillButton("See how it works", {}, appearance = PillAppearance.Ghost)
                    }
                }
                // How it works — Steep/Sections/Feature grid
                Column(verticalArrangement = Arrangement.spacedBy(Space.s24)) {
                    CategoryLabel("How it works")
                    RText("Three things Relay keeps for you.", RelayTheme.type.heading)
                    val cards = listOf(
                        Triple("Agree", "Milestones, fees and revisions — accepted by both sides.", "Every change becomes a new version. Nothing is silently rewritten."),
                        Triple("Remember", "Decisions saved with the message they came from.", "Relay suggests decisions from your conversation. Only the ones you confirm become project memory."),
                        Triple("Hand off", "A brief a replacement can start from.", "If someone becomes unavailable, share a scoped handoff: approved work, decisions to keep and the remaining fee."),
                    )
                    if (narrow) Column(verticalArrangement = Arrangement.spacedBy(Space.s24)) { cards.forEach { NeutralCard(it.second, Modifier.fillMaxWidth(), category = it.first, body = it.third) } }
                    else Row(horizontalArrangement = Arrangement.spacedBy(Space.s24)) { cards.forEach { NeutralCard(it.second, Modifier.weight(1f), category = it.first, body = it.third) } }
                }
                AccentCard(
                    "People approve. Relay remembers.",
                    "“AI only suggests. A decision, an approval or a payment becomes real only when a person confirms it — and a payment shows as verified only after Razorpay confirms it on our server.”",
                    "How Relay treats your project",
                    Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(Space.s24)) {
                    HairlineDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        RText("Relay · Razorpay Buildathon demo · use fictional data only", RelayTheme.type.metadata, color = RelayColors.textSecondary)
                        RText("Payments run in Razorpay test mode", RelayTheme.type.metadata, color = RelayColors.textSecondary)
                    }
                }
            }
        }
        Box(Modifier.height(1.dp))
    }
}
