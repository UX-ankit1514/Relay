package app.relay.demo

import app.relay.data.AppMode
import app.relay.data.AuthUser
import app.relay.data.FileStore
import app.relay.data.KeyValue
import app.relay.data.Platform
import app.relay.data.RelayRepository
import app.relay.data.Router
import app.relay.data.str
import app.relay.nav.Route
import app.relay.ui.AppServices
import app.relay.ui.auth.DemoCast
import app.relay.ui.auth.DemoPersona
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Builds the Demo version: same screens and repository, with in-browser data, sign-in and "server". */
fun demoServices(platform: Platform, router: Router, files: FileStore, kv: KeyValue, mode: AppMode): AppServices {
    val auth = DemoAuthService(kv)
    val db = DemoDb(kv, currentUid = { auth.user?.uid }, newToken = { platform.randomToken(20) }) { DemoSeed.build(platform.now()) }
    return AppServices(auth, RelayRepository(db, DemoBackend(db, auth, platform), platform), router, platform, files, mode)
}

/** One perspective of the story, ready to present. */
data class DemoScenario(val number: Int, val title: String, val persona: DemoPersona, val blurb: String)

val DemoScenarios = listOf(
    DemoScenario(1, "Client reviews & pays", DemoCast[0], "ClearInvoice · Rohan’s Mobile design is waiting for Priya’s review"),
    DemoScenario(2, "Freelancer joins & delivers", DemoCast[1], "Bloom Café · Priya’s invite is waiting for Rohan"),
    DemoScenario(3, "Replacement takes over", DemoCast[2], "FitTrack · Rohan dropped out, Maya’s handoff link is open"),
)

private fun AppServices.demoDb(): DemoDb? = if (mode?.isDemo == true) repo.db as? DemoDb else null

private fun DemoDb.isMember(pid: String, uid: String): Boolean =
    (doc("projects/$pid")?.get("memberIds") as? JsonArray)?.any { it.str() == uid } == true

/** Where a scenario starts, given how far the story has moved on. */
fun demoScenarioTarget(s: AppServices, sc: DemoScenario): Route {
    val db = s.demoDb()
    return when (sc.number) {
        1 -> Route.Project(DemoIds.CLEARINVOICE)
        2 -> if (db?.isMember(DemoIds.BLOOM, demoUid(sc.persona.email)) == true) Route.Project(DemoIds.BLOOM) else Route.Invite(DemoIds.BLOOM_INVITE)
        else -> Route.Handoff(db?.doc("projects/${DemoIds.FITTRACK}")?.get("activeHandoffId").str() ?: DemoIds.FITTRACK_HANDOFF)
    }
}

/**
 * Home for someone with no projects in the Demo version — the replacement. Instead of onboarding,
 * open the handoff that was sent to them.
 */
fun demoHome(s: AppServices, user: AuthUser): Route? {
    val db = s.demoDb() ?: return null
    if (db.children("projects").any { (id, _) -> db.isMember(id, user.uid) }) return null
    val email = user.email.lowercase()
    return db.children("handoffs")
        .filter { (_, h) ->
            val taken = h["replacementUid"].str()
            if (taken != null) taken == user.uid else h["sentTo"].str()?.lowercase() == email
        }
        .maxByOrNull { (_, h) -> (h["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L }
        ?.let { Route.Handoff(it.first) }
}

/** Sample values for the work-submission forms, so the presenter doesn't have to type during the demo. */
object DemoSamples {
    fun figmaLink(label: String): String =
        "https://www.figma.com/design/demo-relay/" + label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
    const val NOTE_FREELANCER = "All frames are in one Figma page, named by size. Pricing stacks as cards on small screens."
    const val NOTE_REPLACEMENT = "Both sizes are done. I followed the approved onboarding flow, kept coral for primary buttons only, and left social sign-in out."
}
