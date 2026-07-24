package com.garfiec.librechat.feature.auth.viewmodel

import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.VerifyTwoFactorOutcome
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TwoFactorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val authRepository = mockk<AuthRepository>(relaxed = true)

    @Before
    fun setup() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun createViewModel() = TwoFactorViewModel(authRepository, initialTempToken = TEMP_TOKEN)

    private fun TwoFactorViewModel.enterDigits(code: String) =
        code.forEachIndexed { index, digit -> onDigitChanged(index, digit.toString()) }

    @Test
    fun `entering six digits verifies the code as TOTP`() = runTest {
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            VerifyTwoFactorOutcome.Success(USER)

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        advanceUntilIdle()

        coVerify { authRepository.verifyTwoFactor(TEMP_TOKEN, "123456", false) }
        assertThat(viewModel.uiState.value.isVerified).isTrue()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `backup mode submits the code as a backup code`() = runTest {
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            VerifyTwoFactorOutcome.Success(USER)

        val viewModel = createViewModel()
        viewModel.toggleBackupMode()
        viewModel.onBackupCodeChanged("  abcd1234  ")
        viewModel.submit()
        advanceUntilIdle()

        coVerify { authRepository.verifyTwoFactor(TEMP_TOKEN, "abcd1234", true) }
        assertThat(viewModel.uiState.value.isVerified).isTrue()
    }

    @Test
    fun `a rejected code surfaces the server's message and clears the spent entry`() = runTest {
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            VerifyTwoFactorOutcome.CodeRejected("Invalid 2FA code or backup code")

        val viewModel = createViewModel()
        viewModel.enterDigits("000000")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("Invalid 2FA code or backup code")
        assertThat(state.isVerified).isFalse()
        assertThat(state.digits).containsExactlyElementsIn(List(6) { "" })
        assertThat(state.codeAttempt).isEqualTo(1)
    }

    @Test
    fun `a connection failure is not reported as a bad code and keeps the entry`() = runTest {
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            VerifyTwoFactorOutcome.ConnectionFailure

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("Couldn't reach the server. Check your connection and try again.")
        assertThat(state.digits.joinToString("")).isEqualTo("123456")
        assertThat(state.codeAttempt).isEqualTo(0)
    }

    @Test
    fun `retrying after a connection failure resubmits the retained code`() = runTest {
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            VerifyTwoFactorOutcome.ConnectionFailure

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        advanceUntilIdle()

        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            VerifyTwoFactorOutcome.Success(USER)
        viewModel.submit()
        advanceUntilIdle()

        coVerify(exactly = 2) { authRepository.verifyTwoFactor(TEMP_TOKEN, "123456", false) }
        assertThat(viewModel.uiState.value.isVerified).isTrue()
    }

    @Test
    fun `an incomplete session clears the spent code and surfaces its own message`() = runTest {
        // A 2xx that accepted the code (single-use) but returned no session: the code is spent, so
        // the boxes clear for a fresh one — but the message must be honest, not a connectivity lie.
        val message = "The server's sign-in response was incomplete. Please try again."
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            VerifyTwoFactorOutcome.SessionIncomplete(message)

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo(message)
        assertThat(state.digits).containsExactlyElementsIn(List(6) { "" })
        assertThat(state.codeAttempt).isEqualTo(1)
        assertThat(state.isVerified).isFalse()
    }

    @Test
    fun `a server error keeps the entry and is not reported as a bad code`() = runTest {
        // A 5xx never evaluated the code, so the entry stays intact for a plain retry.
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            VerifyTwoFactorOutcome.NotEvaluated("Server error. Please try again.")

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("Server error. Please try again.")
        assertThat(state.digits.joinToString("")).isEqualTo("123456")
        assertThat(state.codeAttempt).isEqualTo(0)
        assertThat(state.isVerified).isFalse()
    }

    @Test
    fun `a rate limit keeps the entry so the unevaluated code can be retried`() = runTest {
        // A 429 refuses the request before verification — the code was never judged, so wiping
        // it would force a pointless re-type of a still-valid code.
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            VerifyTwoFactorOutcome.NotEvaluated("Too many verification attempts, please try again after 5 minutes.")

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("Too many verification attempts, please try again after 5 minutes.")
        assertThat(state.digits.joinToString("")).isEqualTo("123456")
        assertThat(state.codeAttempt).isEqualTo(0)
    }

    @Test
    fun `a verify that succeeds after a mode switch still completes sign-in`() = runTest {
        // By the time Success surfaces, the session is already committed in the token store —
        // suppressing it would strand an authenticated user on the backup-code screen.
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } coAnswers {
            delay(10_000)
            VerifyTwoFactorOutcome.Success(USER)
        }

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        runCurrent() // let submit() set isLoading before the repo call suspends

        viewModel.toggleBackupMode()

        val switched = viewModel.uiState.value
        assertThat(switched.isBackupMode).isTrue()
        assertThat(switched.isLoading).isFalse()
        assertThat(switched.digits).containsExactlyElementsIn(List(6) { "" })

        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isVerified).isTrue()
    }

    @Test
    fun `a verify that fails after a mode switch is dropped, not repainted`() = runTest {
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } coAnswers {
            delay(10_000)
            VerifyTwoFactorOutcome.ConnectionFailure
        }

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        runCurrent()

        viewModel.toggleBackupMode()
        advanceUntilIdle()

        // The superseded failure must not resurface on the switched screen: no error, no
        // restored entry, no loading spinner.
        val settled = viewModel.uiState.value
        assertThat(settled.isVerified).isFalse()
        assertThat(settled.isBackupMode).isTrue()
        assertThat(settled.error).isNull()
        assertThat(settled.digits).containsExactlyElementsIn(List(6) { "" })
        assertThat(settled.isLoading).isFalse()
    }

    private companion object {
        const val TEMP_TOKEN = "temp-abc"
        val USER = User(mongoId = "u1", email = "a@b.com")
    }
}
