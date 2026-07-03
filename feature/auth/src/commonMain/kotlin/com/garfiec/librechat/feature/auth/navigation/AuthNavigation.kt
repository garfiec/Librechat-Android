package com.garfiec.librechat.feature.auth.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.auth.screen.ForgotPasswordScreen
import com.garfiec.librechat.feature.auth.screen.LoginScreen
import com.garfiec.librechat.feature.auth.screen.RegisterScreen
import com.garfiec.librechat.feature.auth.screen.ResetPasswordScreen
import com.garfiec.librechat.feature.auth.screen.ServerUrlScreen
import com.garfiec.librechat.feature.auth.screen.TermsScreen
import com.garfiec.librechat.feature.auth.screen.TwoFactorScreen
import com.garfiec.librechat.feature.auth.screen.VerifyEmailScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable sealed interface AuthRoute : NavKey

@Serializable data object ServerUrl : AuthRoute

@Serializable data object Login : AuthRoute

/**
 * Add-account (multi-account) variants of the server-URL + login screens: reached from the account
 * switcher while another account stays live, so their ViewModels run in add mode (pending request
 * identity — the live account's URL/tokens/config are never touched). Distinct routes rather than a
 * flag on [ServerUrl]/[Login], so the NavHost can tell from the back stack alone whether an add
 * flow is still in progress and cancel the pending session once its routes are gone.
 */
@Serializable data object AddAccountServerUrl : AuthRoute

@Serializable data object AddAccountLogin : AuthRoute

/** True for the routes that constitute an in-progress add-account flow. Follow-on screens reached
 *  from add-mode login (register, 2FA, forgot password) are shared routes and intentionally
 *  excluded — they only ever sit above an [AddAccountLogin] entry, so checking these two is enough. */
val NavKey.isAddAccountFlowRoute: Boolean
    get() = this is AddAccountServerUrl || this is AddAccountLogin

@Serializable data object Register : AuthRoute

@Serializable data object ForgotPassword : AuthRoute

@Serializable data class TwoFactor(val tempToken: String) : AuthRoute

@Serializable data class VerifyEmail(val email: String) : AuthRoute

@Serializable data object Terms : AuthRoute

@Serializable data class ResetPassword(val userId: String, val token: String) : AuthRoute

fun EntryProviderScope<NavKey>.authEntries(
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    onAuthComplete: () -> Unit,
) {
    entry<ServerUrl> {
        ServerUrlScreen(
            onServerValidate = { onNavigate(Login) },
        )
    }
    entry<AddAccountServerUrl> {
        // Unlike the onboarding root, this is pushed on top of the live account's stack, so it
        // gets a back affordance to abandon the add flow (popping it cancels the pending session).
        ServerUrlScreen(
            onServerValidate = { onNavigate(AddAccountLogin) },
            onBack = onBack,
            viewModel = koinViewModel(parameters = { parametersOf(true) }),
        )
    }
    entry<AddAccountLogin> {
        // Same screen + ViewModel class as Login; the VM detects add mode via the pending add
        // session (set by the AddAccountServerUrl step) and the auth repository routes the sign-in
        // to the pending server. Success completes the add and lands on the new account's chat.
        LoginScreen(
            onLoginSuccess = onAuthComplete,
            onNavigateToRegister = { onNavigate(Register) },
            onNavigateToForgotPassword = { onNavigate(ForgotPassword) },
            onNavigateToTwoFactor = { tempToken ->
                onNavigate(TwoFactor(tempToken = tempToken))
            },
            onBack = onBack,
        )
    }
    entry<Login> {
        LoginScreen(
            onLoginSuccess = onAuthComplete,
            onNavigateToRegister = { onNavigate(Register) },
            onNavigateToForgotPassword = { onNavigate(ForgotPassword) },
            onNavigateToTwoFactor = { tempToken ->
                onNavigate(TwoFactor(tempToken = tempToken))
            },
        )
    }
    entry<Register> {
        RegisterScreen(
            onRegister = onBack,
            onNavigateToLogin = onBack,
        )
    }
    entry<ForgotPassword> {
        ForgotPasswordScreen(
            onBack = onBack,
        )
    }
    entry<TwoFactor> { key ->
        TwoFactorScreen(
            onVerify = onAuthComplete,
            onBack = onBack,
            tempToken = key.tempToken,
        )
    }
    entry<VerifyEmail> { key ->
        VerifyEmailScreen(
            onVerify = onBack,
            onBack = onBack,
            email = key.email,
        )
    }
    entry<Terms> {
        TermsScreen(
            onAccept = onAuthComplete,
            onBack = onBack,
        )
    }
    entry<ResetPassword> { key ->
        ResetPasswordScreen(
            onResetComplete = onBack,
            onBack = onBack,
            userId = key.userId,
            token = key.token,
        )
    }
}

val authSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(ServerUrl::class, ServerUrl.serializer())
        subclass(Login::class, Login.serializer())
        subclass(AddAccountServerUrl::class, AddAccountServerUrl.serializer())
        subclass(AddAccountLogin::class, AddAccountLogin.serializer())
        subclass(Register::class, Register.serializer())
        subclass(ForgotPassword::class, ForgotPassword.serializer())
        subclass(TwoFactor::class, TwoFactor.serializer())
        subclass(VerifyEmail::class, VerifyEmail.serializer())
        subclass(Terms::class, Terms.serializer())
        subclass(ResetPassword::class, ResetPassword.serializer())
    }
}
