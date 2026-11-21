package app.relay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.relay.data.AuthState
import app.relay.data.AuthUser
import app.relay.design.RelayTheme
import app.relay.model.UserProfile
import app.relay.nav.Route
import app.relay.ui.AppServices
import app.relay.ui.LoadingPage
import app.relay.ui.LocalNavHome
import app.relay.ui.LocalServices
import app.relay.ui.LocalUser
import app.relay.ui.MessagePage
import app.relay.ui.auth.SignInScreen
import app.relay.ui.handoff.HandoffScreen
import app.relay.ui.invite.InviteScreen
import app.relay.ui.landing.LandingScreen
import app.relay.ui.onboarding.FreelancerWaitingScreen
import app.relay.ui.onboarding.IntroScreen
import app.relay.ui.onboarding.InviteFreelancerScreen
import app.relay.ui.onboarding.MilestonesScreen
import app.relay.ui.onboarding.NewProjectScreen
import app.relay.ui.onboarding.WelcomeScreen
import app.relay.ui.project.ProjectScreen
import app.relay.design.StatusKind
import kotlinx.coroutines.flow.first

@Composable
fun App(services: AppServices) {
    RelayTheme {
        CompositionLocalProvider(LocalServices provides services) {
            val auth by services.auth.state.collectAsState()
            val route by services.router.route.collectAsState()
            val user = (auth as? AuthState.SignedIn)?.user
            CompositionLocalProvider(LocalUser provides user, LocalNavHome provides { services.router.navigate(if (user == null) Route.Landing else Route.Landing) }) {
                when (auth) {
                    AuthState.Loading -> LoadingPage()
                    else -> RouteHost(route, user, services)
                }
            }
        }
    }
}

/** Routes that can be opened without an account (context before sign-up, IA §1 principles). */
private fun Route.isPublic() = this is Route.Landing || this is Route.SignIn || this is Route.Invite || this is Route.Handoff || this is Route.NotFound

@Composable
private fun RouteHost(route: Route, user: AuthUser?, services: AppServices) {
    if (user == null && !route.isPublic()) {
        LaunchedEffect(route) { services.router.navigate(Route.SignIn(route.path), replace = true) }
        LoadingPage()
        return
    }
    if (user != null) {
        // Ensure a profile exists for every signed-in person.
        var profile by remember(user.uid) { mutableStateOf<UserProfile?>(null) }
        LaunchedEffect(user.uid) { profile = services.repo.ensureProfile(user) }
        if (profile == null) { LoadingPage(); return }
        if (route is Route.Landing || route is Route.SignIn) {
            HomeRedirect(user, (route as? Route.SignIn)?.next)
            return
        }
    }
    when (route) {
        Route.Landing -> LandingScreen()
        is Route.SignIn -> SignInScreen(route.next)
        Route.Welcome -> WelcomeScreen()
        Route.Intro -> IntroScreen()
        Route.NewProject -> NewProjectScreen()
        is Route.NewMilestones -> MilestonesScreen(route.projectId)
        is Route.NewInvite -> InviteFreelancerScreen(route.projectId)
        is Route.Project -> ProjectScreen(route)
        is Route.Invite -> InviteScreen(route.token)
        is Route.Handoff -> HandoffScreen(route)
        is Route.NotFound -> MessagePage(
            "Not found" to StatusKind.NotStarted, "This page doesn’t exist",
            "The link may be mistyped, or the page was moved.", "Go to Relay" to { services.router.navigate(Route.Landing) },
        )
    }
}

/** Project = home (IA §3C): after sign-in go straight to the project, or to onboarding if there is none. */
@Composable
private fun HomeRedirect(user: AuthUser, next: String?) {
    val services = LocalServices.current
    LaunchedEffect(user.uid, next) {
        if (!next.isNullOrBlank() && next.startsWith("/") && !next.startsWith("/auth")) {
            services.router.navigate(Route.parse(next.substringBefore('?'), next.substringAfter('?', "")), replace = true)
            return@LaunchedEffect
        }
        val profile = services.repo.observeProfile(user.uid).first()
        val projects = services.repo.observeMyProjects(user.uid).first()
        val target = when {
            projects.isNotEmpty() -> Route.Project(projects.first().id)
            profile?.intent == null -> Route.Welcome
            profile.intent == "freelancing" -> Route.Welcome
            !profile.introSeen -> Route.Intro
            else -> Route.NewProject
        }
        services.router.navigate(target, replace = true)
    }
    LoadingPage()
}

@Composable
fun FreelancerHome() = FreelancerWaitingScreen()
