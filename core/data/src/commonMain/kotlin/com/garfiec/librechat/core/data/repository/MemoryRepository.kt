package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Memory
import com.garfiec.librechat.core.model.MemoryPreferences
import com.garfiec.librechat.core.model.request.CreateMemoryRequest
import com.garfiec.librechat.core.model.request.UpdateMemoryPreferencesRequest
import com.garfiec.librechat.core.model.request.UpdateMemoryRequest

/**
 * Repository for user memory CRUD operations.
 *
 * A memory is identified by its key *within a partition*: `agentId == null` is the shared
 * personal pool, a non-null `agentId` is that agent's private pool. The same key can exist in
 * both, so every mutation must carry the [Memory.agentId] of the entry it targets — omitting it
 * edits or deletes the shared-pool entry of the same name instead (or 404s).
 */
interface MemoryRepository {
    suspend fun getMemories(): Result<List<Memory>>
    suspend fun createMemory(request: CreateMemoryRequest): Result<Memory>
    suspend fun updatePreferences(request: UpdateMemoryPreferencesRequest): Result<MemoryPreferences>
    suspend fun updateMemory(key: String, request: UpdateMemoryRequest, agentId: String? = null): Result<Memory>
    suspend fun deleteMemory(key: String, agentId: String? = null): Result<Unit>
}
