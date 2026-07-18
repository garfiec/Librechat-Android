package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.FileObject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.path

class FilesExtApi constructor(
    private val client: HttpClient,
) {
    suspend fun getAgentFiles(agentId: String): List<FileObject> =
        client.get {
            url { path("api/files/agent/$agentId") }
        }.body()
}
