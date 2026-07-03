package com.garfiec.librechat.shared.navigation

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Immutable
data class VersionMismatchState(
    val supportedVersion: String,
    val backendVersion: String,
)

class VersionCheckStateHolder(
    private val configRepository: ConfigRepository,
    private val settingsDataStore: SettingsDataStore,
    private val serverUrlProvider: ServerUrlProvider,
    private val scope: CoroutineScope,
) {

    private val _versionMismatch = MutableStateFlow<VersionMismatchState?>(null)
    val versionMismatch: StateFlow<VersionMismatchState?> = _versionMismatch.asStateFlow()

    // serverId the current banner describes. This holder is Activity-scoped and survives account
    // switches, so an unreachable re-check must be able to tell a stale banner carried over from a
    // PREVIOUS server (clear it) apart from a transient blip on the still-incompatible CURRENT server
    // (keep it) — clearing the latter would silently hide a real incompatibility until the next
    // successful check.
    private var mismatchServerId: String? = null

    fun checkBackendVersion() {
        scope.launch {
            val serverId = runCatching { deriveServerId(serverUrlProvider.getBaseUrl()).value }.getOrNull()
            when (val result = configRepository.checkBackendVersion()) {
                is Result.Success -> {
                    val checkResult = result.data
                    val detectedVersion = checkResult.backendVersion
                    if (!checkResult.isCompatible && detectedVersion != null) {
                        val dismissedVersion = settingsDataStore.dismissedVersionWarning.first()
                        if (dismissedVersion != detectedVersion) {
                            _versionMismatch.value = VersionMismatchState(
                                supportedVersion = checkResult.supportedVersion,
                                backendVersion = detectedVersion,
                            )
                            mismatchServerId = serverId
                        } else {
                            clearBanner()
                        }
                    } else {
                        // Compatible (or version undetectable): positive information about THIS server,
                        // so clear any banner — including one left over from a previous server.
                        clearBanner()
                    }
                }
                is Result.Error -> {
                    // We couldn't reach the server, so we learned nothing about ITS compatibility. Only
                    // drop a banner that belongs to a DIFFERENT server (stale carry-over after a switch);
                    // keep one for the current server so a transient blip can't hide a real mismatch. A
                    // real mismatch also re-surfaces on the next successful check.
                    if (mismatchServerId != null && mismatchServerId != serverId) {
                        clearBanner()
                    }
                    Logger.w(result.exception) { "Failed to check backend version: ${result.message}" }
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun dismissVersionWarning() {
        clearBanner()
    }

    fun dismissVersionWarningPermanently() {
        val backendVersion = _versionMismatch.value?.backendVersion
        clearBanner()
        if (backendVersion != null) {
            scope.launch {
                settingsDataStore.setDismissedVersionWarning(backendVersion)
            }
        }
    }

    private fun clearBanner() {
        _versionMismatch.value = null
        mismatchServerId = null
    }
}
