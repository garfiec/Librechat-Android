package com.garfiec.librechat.core.common.result

import com.garfiec.librechat.core.common.di.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class SafeApiCallTest {

    @Test
    fun safeApiCallReturnsSuccessOnSuccessfulBlock() = runTest {
        val result = safeApiCall { "hello" }
        assertIs<Result.Success<String>>(result)
        assertEquals("hello", result.data)
    }

    /**
     * The exception is kept for logging and diagnosis, but its message is NOT the user-facing text:
     * Ktor quotes the request URL in its own messages, so passing `e.message` through would render
     * a gateway redirect and its `meta=` JWT in an error banner.
     */
    @Test
    fun safeApiCallReturnsErrorOnException() = runTest {
        val result = safeApiCall<String> { throw IllegalStateException("boom") }
        assertIs<Result.Error>(result)
        assertEquals(FailureMessages.UNKNOWN, result.message)
        assertEquals(FailureKind.Unknown, result.kind)
        assertIs<IllegalStateException>(result.exception)
        assertEquals("boom", result.exception?.message)
    }

    @Test
    fun safeApiCallPropagatesCancellation() = runTest {
        assertFailsWith<CancellationException> {
            safeApiCall<String> { throw CancellationException("cancelled") }
        }
    }

    /**
     * Asserts on the interceptor rather than a thread name so it holds on both platforms. `runTest`
     * installs a dispatcher of its own, so a missing hop fails this rather than passing by
     * coincidence.
     */
    @Test
    fun safeApiCallRunsItsBlockOnTheIoDispatcher() = runTest {
        var inside: ContinuationInterceptor? = null
        val result = safeApiCall {
            inside = currentCoroutineContext()[ContinuationInterceptor]
            "ok"
        }
        assertIs<Result.Success<String>>(result)
        assertSame(ioDispatcher, inside)
    }

    @Test
    fun onApiDispatcherRunsItsBlockOnTheIoDispatcher() = runTest {
        val inside = onApiDispatcher { currentCoroutineContext()[ContinuationInterceptor] }
        assertSame(ioDispatcher, inside)
    }

    @Test
    fun onApiDispatcherLetsFailuresEscape() = runTest {
        assertFailsWith<IllegalStateException> {
            onApiDispatcher { throw IllegalStateException("boom") }
        }
    }

    @Test
    fun isSuccessReturnsTrueForSuccess() {
        val result: Result<String> = Result.Success("data")
        assertTrue(result.isSuccess())
        assertFalse(result.isError())
        assertFalse(result.isLoading())
    }

    @Test
    fun isErrorReturnsTrueForError() {
        val result: Result<String> = Result.Error(message = "fail")
        assertTrue(result.isError())
        assertFalse(result.isSuccess())
    }

    @Test
    fun isLoadingReturnsTrueForLoading() {
        val result: Result<String> = Result.Loading
        assertTrue(result.isLoading())
    }

    @Test
    fun getOrNullReturnsDataForSuccess() {
        val result: Result<String> = Result.Success("data")
        assertEquals("data", result.getOrNull())
    }

    @Test
    fun getOrNullReturnsNullForError() {
        val result: Result<String> = Result.Error(message = "fail")
        assertNull(result.getOrNull())
    }

    @Test
    fun getOrThrowReturnsDataForSuccess() {
        val result: Result<String> = Result.Success("data")
        assertEquals("data", result.getOrThrow())
    }

    @Test
    fun getOrThrowThrowsForErrorWithException() {
        val original = IllegalArgumentException("bad arg")
        val result: Result<String> = Result.Error(original, "bad arg")
        try {
            result.getOrThrow()
            fail("Should have thrown")
        } catch (e: Exception) {
            assertSame(original, e)
        }
    }

    @Test
    fun getOrThrowThrowsForLoading() {
        val result: Result<String> = Result.Loading
        try {
            result.getOrThrow()
            fail("Should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("Result is still loading", e.message)
        }
    }
}
