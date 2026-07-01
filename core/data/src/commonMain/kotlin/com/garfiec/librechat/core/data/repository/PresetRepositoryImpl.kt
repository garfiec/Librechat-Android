package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.Preset
import com.garfiec.librechat.core.network.api.PresetsApi

class PresetRepositoryImpl(
    private val presetsApi: PresetsApi,
    activeAccountProvider: ActiveAccountProvider,
) : PresetRepository {

    // Account-keyed in-memory cache: the only isolation tier for presets (no Room/accountId scoping).
    private val cache = AccountKeyedCache<List<Preset>>(activeAccountProvider)

    override suspend fun getAll(): Result<List<Preset>> {
        return safeApiCall {
            cache.getOrFetch { presetsApi.getPresets() }
        }
    }

    override suspend fun create(preset: Preset): Result<Preset> {
        return safeApiCall {
            val created = presetsApi.createPreset(preset)
            invalidateCache()
            created
        }
    }

    override suspend fun update(preset: Preset): Result<Preset> {
        return safeApiCall {
            val updated = presetsApi.updatePreset(preset)
            invalidateCache()
            updated
        }
    }

    override suspend fun delete(presetId: String): Result<Unit> {
        return safeApiCall {
            presetsApi.deletePreset(presetId)
            invalidateCache()
        }
    }

    private suspend fun invalidateCache() = cache.invalidate()
}
