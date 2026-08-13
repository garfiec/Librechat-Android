# core:common

Pure Kotlin utilities shared by all modules. This is the lowest layer -- no other `:core:*` module is a dependency.

## What This Module Provides

- **Result sealed class** (`result/Result.kt`): `Success<T>`, `Error(exception, message)`, `Loading`. Used by repositories and ViewModels to propagate outcomes.
- **Dispatcher & Scope DI** (`di/CommonModule.kt`): Named Koin qualifiers (`named("io")`, `named("default")`, `named("main")`) for dispatchers and `named("applicationScope")` for coroutine scope. Always inject dispatchers -- never hardcode `Dispatchers.IO`. The sole exception is `safeApiCall` / `onApiDispatcher`, which read the platform `ioDispatcher` directly; see below for why.
- **Extensions** (`extensions/`): `StringExt`, `DateExt`, `FlowExt` (includes `retryWithBackoff`, `throttleFirst`).
- **ConnectivityObserver**: Wraps Android `ConnectivityManager.NetworkCallback` to detect network changes. Used by SSE reconnection logic.

## safeApiCall Pattern

All repository implementations use this to wrap network calls. It does two things: run the block on
the IO dispatcher, and turn a failure into a displayable `Result.Error` (`toSafeError` classifies it
into a `FailureKind` and screens server text before it can reach the UI).

```kotlin
suspend fun <T> safeApiCall(block: suspend () -> T): Result<T> =
    try {
        Result.Success(withContext(ioDispatcher) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.toSafeError()
    }
```

This lives here (not in `:core:network`) because repositories in `:core:data` call it.

**The dispatcher hop is deliberately here rather than at the call sites**, and it is the one
sanctioned exception to the "always inject dispatchers" rule stated above. Ktor's engine does its socket
I/O on engine threads, but the continuation resumes in the caller's context — so body
deserialization, the auth plugin's `401` branch, the token refresh it drives and that refresh's
keystore-backed reads all run wherever the call was launched from. Several cold-start paths launch
from `viewModelScope`, which put all of it on the UI thread (#326). One hop in the wrapper every API
call already funnels through covers all of them and cannot be forgotten by a repository added later.
A Ktor plugin was the other candidate. It would have to cover two pipelines rather than one — the
send, and the response pipeline that `body()` drives — and it still would not cover the Room and
DataStore work that sits in the same block as the call.

`onApiDispatcher { }` is the same hop **without** the error mapping, for the handful of calls that
classify their own failures (a bespoke validation message, or a first attempt whose failure is
expected and must not be logged as an error). Prefer `safeApiCall` unless you are one of those.

## Rules

- **Pure Kotlin preferred.** Minimal Android dependencies (only what ConnectivityObserver and Koin require).
- **No network or data dependencies.** This module must not depend on `:core:network`, `:core:data`, or `:core:model`.
- Dependencies: `coroutines-core`, `coroutines-android`, Koin.
- Convention plugin: `librechat.mobile.library` + `librechat.mobile.koin`.
