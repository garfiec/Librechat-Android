package com.garfiec.librechat.core.model

/**
 * Upstream `PrincipalType` enum (accessPermissions.ts:16-21). Wire values are the
 * lowercase strings.
 */
object PrincipalType {
    const val USER = "user"
    const val GROUP = "group"
    const val PUBLIC = "public"
    const val ROLE = "role"
}

/**
 * Resource types the permissions API accepts (accessPermissions.ts:45-50). Used in
 * the URL path: `/api/permissions/{resourceType}/{resourceId}`.
 */
object ResourceType {
    const val AGENT = "agent"
    const val PROMPT_GROUP = "promptGroup"
    const val MCP_SERVER = "mcpServer"
    const val REMOTE_AGENT = "remoteAgent"
    const val SKILL = "skill"
}
