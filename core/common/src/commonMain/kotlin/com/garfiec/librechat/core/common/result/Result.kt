package com.garfiec.librechat.core.common.result

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException

sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>

    /**
     * [message] is safe to render: it is either app-authored or server text that passed
     * [looksLikeUserMessage]. The untrimmed detail lives on [exception] and in the log — never put
     * `exception.message` on screen. [kind] lets a UI layer branch (retry affordance, and localized
     * copy later) without parsing text.
     */
    data class Error(
        val exception: Throwable? = null,
        val message: String? = null,
        val kind: FailureKind = FailureKind.Unknown,
    ) : Result<Nothing>

    data object Loading : Result<Nothing>
}

fun <T> Result<T>.isSuccess(): Boolean = this is Result.Success
fun <T> Result<T>.isError(): Boolean = this is Result.Error
fun <T> Result<T>.isLoading(): Boolean = this is Result.Loading

fun <T> Result<T>.getOrNull(): T? = (this as? Result.Success)?.data
fun <T> Result<T>.getOrThrow(): T = when (this) {
    is Result.Success -> data
    is Result.Error -> throw exception ?: IllegalStateException(message ?: "Unknown error")
    is Result.Loading -> throw IllegalStateException("Result is still loading")
}

suspend fun <T> safeApiCall(block: suspend () -> T): Result<T> =
    try {
        Result.Success(block())
    } catch (e: CancellationException) {
        // Cooperative cancellation must propagate — callers rely on cancel()ed jobs
        // not writing stale errors back to state (e.g. SettingsViewModel.loadUserJob).
        throw e
    } catch (e: Exception) {
        e.toSafeError()
    }

/**
 * Turns a caught failure into a [Result.Error] whose message is fit to display, and sends the full
 * detail to the log instead.
 *
 * Never hand `exception.message` to the UI: Ktor composes its messages out of the request URL, so
 * an access gateway's redirect would render on screen complete with its `meta=` JWT (issue #287).
 */
fun Throwable.toSafeError(): Result.Error {
    val kind = classifyFailure(this)
    Logger.e(throwable = this) { "Request failed (${kind.name})" }
    // Only response-body text is screened; the app's own wording is trusted as-is. See
    // [ApiException.serverAuthored].
    val apiMessage = (this as? ApiException)?.let { api ->
        if (api.serverAuthored) api.message.takeIf(::looksLikeUserMessage) else api.message
    }
    return Result.Error(
        exception = this,
        message = apiMessage ?: kind.defaultMessage(),
        kind = kind,
    )
}
