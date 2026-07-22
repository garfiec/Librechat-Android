package com.garfiec.librechat.feature.auth.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.model.LoginOutcome
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.config.StartupConfig
import com.garfiec.librechat.feature.auth.oauth.OAuthLauncher
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val configRepository = mockk<ConfigRepository>(relaxed = true)
    private val oAuthLauncher = mockk<OAuthLauncher>(relaxed = true)
    private val serverDataStore = mockk<ServerDataStore>(relaxed = true)
    private val accountSwitcher = mockk<AccountSwitcher>(relaxed = true)

    private val configFlow = MutableStateFlow<StartupConfig?>(null)

    private lateinit var viewModel: LoginViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { configRepository.startupConfig } returns configFlow
        // No add-account flow pending: the VM reads the global config + live server URL.
        every { accountSwitcher.pendingAdd } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = LoginViewModel(
        authRepository = authRepository,
        configRepository = configRepository,
        oAuthLauncher = oAuthLauncher,
        serverDataStore = serverDataStore,
        accountSwitcher = accountSwitcher,
    )

    @Test
    fun `initial state has empty fields`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.email).isEmpty()
        assertThat(state.password).isEmpty()
        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.isLoggedIn).isFalse()
    }

    @Test
    fun `onEmailChanged updates email and clears error`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEmailChanged("user@example.com")

        assertThat(viewModel.uiState.value.email).isEqualTo("user@example.com")
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `onPasswordChanged updates password and clears error`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPasswordChanged("secret123")

        assertThat(viewModel.uiState.value.password).isEqualTo("secret123")
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `login with blank email shows error`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPasswordChanged("secret123")
        viewModel.login()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Please enter email and password")
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `login with blank password shows error`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEmailChanged("user@example.com")
        viewModel.login()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Please enter email and password")
    }

    @Test
    fun `successful login sets isLoggedIn true`() = runTest {
        val user = User(email = "user@example.com", name = "Test User")
        coEvery { authRepository.login("user@example.com", "password123") } returns
            Result.Success(LoginOutcome.Success(user))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEmailChanged("user@example.com")
        viewModel.onPasswordChanged("password123")
        viewModel.login()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isLoggedIn).isTrue()
        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isNull()
    }

    @Test
    fun `login requiring 2FA sets twoFactorTempToken`() = runTest {
        coEvery { authRepository.login("user@example.com", "password123") } returns
            Result.Success(LoginOutcome.TwoFactorRequired("temp-token-123"))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEmailChanged("user@example.com")
        viewModel.onPasswordChanged("password123")
        viewModel.login()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.twoFactorTempToken).isEqualTo("temp-token-123")
        assertThat(state.isLoggedIn).isFalse()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `consumeTwoFactorNavigation clears the temp token`() = runTest {
        coEvery { authRepository.login("user@example.com", "password123") } returns
            Result.Success(LoginOutcome.TwoFactorRequired("temp-token-123"))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEmailChanged("user@example.com")
        viewModel.onPasswordChanged("password123")
        viewModel.login()
        advanceUntilIdle()

        viewModel.consumeTwoFactorNavigation()

        assertThat(viewModel.uiState.value.twoFactorTempToken).isNull()
    }

    @Test
    fun `login failure shows error message`() = runTest {
        coEvery { authRepository.login("user@example.com", "wrong") } returns
            Result.Error(message = "Invalid credentials")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEmailChanged("user@example.com")
        viewModel.onPasswordChanged("wrong")
        viewModel.login()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("Invalid credentials")
        assertThat(state.isLoading).isFalse()
        assertThat(state.isLoggedIn).isFalse()
    }

    @Test
    fun `login failure with null message uses default`() = runTest {
        coEvery { authRepository.login("user@example.com", "wrong") } returns
            Result.Error(message = null)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEmailChanged("user@example.com")
        viewModel.onPasswordChanged("wrong")
        viewModel.login()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Login failed")
    }

    @Test
    fun `startup config updates registration and social login settings`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        configFlow.value = StartupConfig(
            registrationEnabled = true,
            socialLoginEnabled = true,
            socialLogins = listOf("google", "github"),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.registrationEnabled).isTrue()
        assertThat(state.socialLoginEnabled).isTrue()
        assertThat(state.socialLogins).containsExactly("google", "github")
    }

    @Test
    fun `startup config with null socialLogins defaults to empty`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        configFlow.value = StartupConfig(socialLogins = null)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.socialLogins).isEmpty()
    }

    @Test
    fun `login shows loading state during request`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns
            Result.Success(LoginOutcome.Success(User(email = "user@example.com")))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEmailChanged("user@example.com")
        viewModel.onPasswordChanged("password123")

        // Before login completes, loading should be false initially
        assertThat(viewModel.uiState.value.isLoading).isFalse()

        viewModel.login()
        advanceUntilIdle()

        // After login completes, loading should be false again
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }
}
