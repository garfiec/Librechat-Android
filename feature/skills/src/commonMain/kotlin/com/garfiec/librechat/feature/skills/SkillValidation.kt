package com.garfiec.librechat.feature.skills

/**
 * Client-side skill field validation mirroring upstream constants
 * (`packages/data-provider/src/types/skills.ts`) so the builder surfaces the
 * same limits the server enforces with 400s.
 */
object SkillValidation {
    const val NAME_MAX_LENGTH = 64
    const val DESCRIPTION_MAX_LENGTH = 1024
    const val BODY_MAX_LENGTH = 100_000

    /** Upstream `SKILL_NAME_PATTERN = /^[a-z0-9][a-z0-9-]*$/`. */
    private val NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")

    fun isNameValid(name: String): Boolean =
        name.isNotEmpty() && name.length <= NAME_MAX_LENGTH && NAME_PATTERN.matches(name)

    fun isDescriptionValid(description: String): Boolean =
        description.isNotBlank() && description.length <= DESCRIPTION_MAX_LENGTH

    fun isBodyValid(body: String): Boolean = body.length <= BODY_MAX_LENGTH
}
