package com.garfiec.librechat.feature.chat.components.artifact

/** Keeps large artifact payloads out of the full-screen route key. */
class ArtifactViewerHandoff {

    data class Entry(val artifact: Artifact, val versions: List<Artifact>)

    private var pending: Entry? = null

    fun put(artifact: Artifact, versions: List<Artifact>) {
        pending = Entry(artifact, versions)
    }

    /** Returns the pending entry only when it was staged for [identifier]/[version]. */
    fun peek(identifier: String, version: Int): Entry? {
        val current = pending ?: return null
        val a = current.artifact
        return if (a.identifier == identifier && a.version == version) current else null
    }

    fun clear() {
        pending = null
    }
}
