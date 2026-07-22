package com.garfiec.librechat.feature.auth.viewmodel

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.model.User
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns Result.Success(USER)

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        advanceUntilIdle()

        coVerify { authRepository.verifyTwoFactor(TEMP_TOKEN, "123456", false) }
        assertThat(viewModel.uiState.value.isVerified).isTrue()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `backup mode submits the code as a backup code`() = runTest {
        // The backend verifies TOTP and backup codes by different fields, so the mode has to travel
        // with the code — a backup code sent as a TOTP token can only ever fail.
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns Result.Success(USER)

        val viewModel = createViewModel()
        viewModel.toggleBackupMode()
        viewModel.onBackupCodeChanged("  abcd1234  ")
        viewModel.submit()
        advanceUntilIdle()

        coVerify { authRepository.verifyTwoFactor(TEMP_TOKEN, "abcd1234", true) }
        assertThat(viewModel.uiState.value.isVerified).isTrue()
    }

    @Test
    fun `surfaces the server's message on a rejected code`() = runTest {
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            Result.Error(ApiException(401, "Your temporary session has expired. Please sign in again."))

        val viewModel = createViewModel()
        viewModel.enterDigits("000000")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("Your temporary session has expired. Please sign in again.")
        assertThat(state.isVerified).isFalse()
        // The entry is cleared so the user retypes rather than resubmitting the rejected code.
        assertThat(state.digits).containsExactlyElementsIn(List(6) { "" })
    }

    @Test
    fun `falls back to a generic message when the failure carries no server message`() = runTest {
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            Result.Error(java.io.IOException("connection reset"), "connection reset")

        val viewModel = createViewModel()
        viewModel.enterDigits("000000")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Invalid code. Please try again.")
    }

    private companion object {
        const val TEMP_TOKEN = "temp-abc"
        val USER = User(mongoId = "u1", email = "a@b.com")
    }
}
