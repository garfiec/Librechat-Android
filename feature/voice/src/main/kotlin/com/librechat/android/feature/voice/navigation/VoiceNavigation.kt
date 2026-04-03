package com.librechat.android.feature.voice.navigation

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.librechat.android.core.ui.components.ScreenTransitionWrapper
import com.librechat.android.feature.voice.screen.VoiceHomeScreen
import com.librechat.android.feature.voice.screen.VoiceSessionScreen

const val VOICE_HOME_ROUTE = "voice_home"
const val VOICE_SESSION_ROUTE =
    "voice_session?conversationId={conversationId}&endpoint={endpoint}&model={model}&agentId={agentId}"

fun NavController.navigateToVoiceHome() {
    navigate(VOICE_HOME_ROUTE) {
        launchSingleTop = true
    }
}

fun NavGraphBuilder.voiceGraph(
    navController: NavController,
) {
    composable(VOICE_HOME_ROUTE) {
        ScreenTransitionWrapper(transition) {
            VoiceHomeScreen(
                onStartNewSession = { endpoint, model, agentId ->
                    val endpointEncoded = Uri.encode(endpoint)
                    val modelEncoded = Uri.encode(model)
                    val agentEncoded = Uri.encode(agentId)
                    navController.navigate(
                        "voice_session?endpoint=$endpointEncoded&model=$modelEncoded&agentId=$agentEncoded",
                    )
                },
                onResumeConversation = { conversationId ->
                    val encodedId = Uri.encode(conversationId)
                    navController.navigate("voice_session?conversationId=$encodedId")
                },
                onBack = { navController.popBackStack() },
            )
        }
    }

    composable(
        route = VOICE_SESSION_ROUTE,
        arguments = listOf(
            navArgument("conversationId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("endpoint") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("model") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("agentId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        ScreenTransitionWrapper(transition) {
            VoiceSessionScreen(
                conversationId = entry.arguments?.getString("conversationId"),
                endpoint = entry.arguments?.getString("endpoint"),
                model = entry.arguments?.getString("model"),
                agentId = entry.arguments?.getString("agentId"),
                onClose = { navController.popBackStack() },
            )
        }
    }
}
