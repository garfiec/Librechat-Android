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
        assertThat(state.digits).containsExactlyElementsIn(List(6) { "" })
        assertThat(state.codeAttempt).isEqualTo(1)
    }

    @Test
    fun `a network failure is not reported as a bad code and keeps the entry`() = runTest {
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            Result.Error(java.io.IOException("connection reset"), "connection reset")

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("Couldn't reach the server. Check your connection and try again.")
        assertThat(state.digits.joinToString("")).isEqualTo("123456")
        assertThat(state.codeAttempt).isEqualTo(0)
    }

    @Test
    fun `retrying after a network failure resubmits the retained code`() = runTest {
        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns
            Result.Error(java.io.IOException("connection reset"), "connection reset")

        val viewModel = createViewModel()
        viewModel.enterDigits("123456")
        advanceUntilIdle()

        coEvery { authRepository.verifyTwoFactor(any(), any(), any()) } returns Result.Success(USER)
        viewModel.submit()
        advanceUntilIdle()

        coVerify(exactly = 2) { authRepository.verifyTwoFactor(TEMP_TOKEN, "123456", false) }
        assertThat(viewModel.uiState.value.isVerified).isTrue()
    }

    private companion object {
        const val TEMP_TOKEN = "temp-abc"
        val USER = User(mongoId = "u1", email = "a@b.com")
    }
}
