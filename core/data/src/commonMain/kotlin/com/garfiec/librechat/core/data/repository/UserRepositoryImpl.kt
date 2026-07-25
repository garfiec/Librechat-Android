package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.request.ResendVerificationRequest
import com.garfiec.librechat.core.model.request.VerifyEmailRequest
import com.garfiec.librechat.core.model.response.TermsAcceptResponse
import com.garfiec.librechat.core.model.response.TermsResponse
import com.garfiec.librechat.core.network.api.UserApi

class UserRepositoryImpl(
    private val userApi: UserApi,
) : UserRepository {

    override suspend fun getUser(): Result<User> = safeApiCall {
        userApi.getUser()
    }

    override suspend fun deleteUser(token: String?, backupCode: String?): Result<Unit> = safeApiCall {
        userApi.deleteUser(token = token, backupCode = backupCode)
    }

    override suspend fun uploadAvatar(imageBytes: ByteArray): Result<User> = safeApiCall {
        userApi.updateAvatar(imageBytes)
    }

    override suspend fun verifyEmail(token: String): Result<Unit> = safeApiCall {
        userApi.verifyEmail(VerifyEmailRequest(token = token))
    }

    override suspend fun resendVerification(email: String): Result<Unit> = safeApiCall {
        userApi.resendVerification(ResendVerificationRequest(email = email))
    }

    override suspend fun getTerms(): Result<TermsResponse> = safeApiCall {
        userApi.getTerms()
    }

    override suspend fun acceptTerms(): Result<TermsAcceptResponse> = safeApiCall {
        userApi.acceptTerms()
    }
}
