package app.relay.nav

/** IA §3A sitemap → routes. Paths are real URLs (History API), so links land in context. */
sealed interface Route {
    val path: String

    data object Landing : Route { override val path = "/" }
    data class SignIn(val next: String? = null) : Route {
        override val path = if (next.isNullOrBlank()) "/auth" else "/auth?next=" + encode(next)
    }
    data object Welcome : Route { override val path = "/welcome" }
    data object Intro : Route { override val path = "/welcome/intro" }
    data object NewProject : Route { override val path = "/new" }
    data class NewMilestones(val projectId: String) : Route { override val path = "/new/$projectId/milestones" }
    data class NewInvite(val projectId: String) : Route { override val path = "/new/$projectId/invite" }

    /** Client / original-freelancer workspace. */
    data class Project(val projectId: String, val tab: ProjectTab = ProjectTab.Overview, val sub: String? = null) : Route {
        override val path: String = buildString {
            append("/p/").append(projectId)
            if (tab != ProjectTab.Overview) append('/').append(tab.slug)
            if (sub != null) append('/').append(sub)
        }
    }

    data class Invite(val token: String) : Route { override val path = "/invite/$token" }

    /** Replacement (scoped) workspace — reached only via a handoff link. */
    data class Handoff(val token: String, val tab: HandoffTab = HandoffTab.Brief) : Route {
        override val path = if (tab == HandoffTab.Brief) "/h/$token" else "/h/$token/${tab.slug}"
    }

    data class NotFound(override val path: String) : Route

    companion object {
        fun parse(pathname: String, search: String): Route {
            val parts = pathname.trim('/').split('/').filter { it.isNotEmpty() }
            val q = parseQuery(search)
            return when {
                parts.isEmpty() -> Landing
                parts == listOf("auth") -> SignIn(q["next"])
                parts == listOf("welcome") -> Welcome
                parts == listOf("welcome", "intro") -> Intro
                parts == listOf("new") -> NewProject
                parts.size == 3 && parts[0] == "new" && parts[2] == "milestones" -> NewMilestones(parts[1])
                parts.size == 3 && parts[0] == "new" && parts[2] == "invite" -> NewInvite(parts[1])
                parts.size >= 2 && parts[0] == "p" -> {
                    val tab = parts.getOrNull(2)?.let { ProjectTab.fromSlug(it) } ?: ProjectTab.Overview
                    Project(parts[1], tab, parts.getOrNull(3))
                }
                parts.size == 2 && parts[0] == "invite" -> Invite(parts[1])
                parts.size >= 2 && parts[0] == "h" -> Handoff(parts[1], parts.getOrNull(2)?.let { HandoffTab.fromSlug(it) } ?: HandoffTab.Brief)
                else -> NotFound(pathname)
            }
        }

        private fun parseQuery(search: String): Map<String, String> =
            search.removePrefix("?").split('&').filter { it.contains('=') }.associate {
                val (k, v) = it.split('=', limit = 2); k to decode(v)
            }

        private fun encode(s: String) = s.replace("%", "%25").replace("&", "%26").replace("?", "%3F").replace("=", "%3D").replace(" ", "%20")
        private fun decode(s: String) = s.replace("%20", " ").replace("%3D", "=").replace("%3F", "?").replace("%26", "&").replace("%2F", "/").replace("%25", "%")
    }
}

enum class ProjectTab(val label: String, val slug: String) {
    Overview("Overview", "overview"),
    Agreement("Agreement", "agreement"),
    Conversation("Conversation", "conversation"),
    Decisions("Decisions", "decisions"),
    Files("Files", "files"),
    Handoff("Handoff", "handoff"),
    Milestone("Milestone", "m"),
    Review("Review", "review"),
    Payment("Payment", "pay");

    companion object {
        fun fromSlug(s: String) = entries.firstOrNull { it.slug == s } ?: Overview
        val clientTabs = listOf(Overview, Agreement, Conversation, Decisions, Files, Handoff)
        val freelancerTabs = listOf(Overview, Agreement, Conversation, Decisions, Files)
    }
}

enum class HandoffTab(val label: String, val slug: String) {
    Brief("Brief", "brief"), Terms("Terms", "terms"), Work("My work", "work"), Status("Status", "status");
    companion object { fun fromSlug(s: String) = entries.firstOrNull { it.slug == s } ?: Brief }
}
