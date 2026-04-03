package com.librechat.android.core.network.api

import com.librechat.android.core.model.User
import com.librechat.android.core.model.UserFavorite
import com.librechat.android.core.model.request.OtpVerificationRequest
import com.librechat.android.core.model.request.ResendVerificationRequest
import com.librechat.android.core.model.request.VerifyEmailRequest
import com.librechat.android.core.model.response.TermsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.path
import kotlinx.serialization.Serializable

@Serializable
data class UserUpdateRequest(
    val name: String? = null,
    val username: String? = null,
)

@Serializable
data class UpdateFavoritesRequest(
    val favorites: List<UserFavorite>,
)

@Serializable
data class UpdatePluginsRequest(
    val plugins: List<String>,
)

class UserApi constructor(
    private val client: HttpClient,
) {
    suspend fun getUser(): User =
        client.get {
            url { path("api/user") }
        }.body()

    suspend fun updateUser(update: UserUpdateRequest): User =
        client.post {
            url { path("api/user") }
            setBody(update)
        }.body()

    suspend fun deleteUser(token: String? = null, backupCode: String? = null) {
        client.delete {
            url { path("api/user/delete") }
            if (token != null || backupCode != null) {
                setBody(OtpVerificationRequest(token = token, backupCode = backupCode))
            }
        }
    }

    suspend fun verifyEmail(request: VerifyEmailRequest) {
        client.post {
            url { path("api/user/verify") }
            setBody(request)
        }
    }

    suspend fun resendVerification(request: ResendVerificationRequest) {
        client.post {
            url { path("api/user/verify/resend") }
            setBody(request)
        }
    }

    suspend fun getTerms(): TermsResponse =
        client.get {
            url { path("api/user/terms") }
        }.body()

    suspend fun acceptTerms() {
        client.post {
            url { path("api/user/terms/accept") }
        }
    }

    suspend fun updateAvatar(imageBytes: ByteArray): User =
        client.submitFormWithBinaryData(
            formData = formData {
                append("file", imageBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"avatar.png\"")
                    append(HttpHeaders.ContentType, "image/png")
                })
                append("manual", "true")
            },
        ) {
            url { path("api/user/avatar") }
        }.body()

    suspend fun getFavorites(): List<UserFavorite> =
        client.get {
            // LibreChat mounts favorites under user settings (see api/server/routes/settings.js).
            url { path("api/user/settings/favorites") }
        }.body()

    suspend fun updateFavorites(favorites: List<UserFavorite>): User =
        client.post {
            url { path("api/user/settings/favorites") }
            setBody(UpdateFavoritesRequest(favorites = favorites))
        }.body()

    suspend fun updatePlugins(plugins: List<String>): User =
        client.post {
            url { path("api/user/plugins") }
            setBody(UpdatePluginsRequest(plugins = plugins))
        }.body()
}
