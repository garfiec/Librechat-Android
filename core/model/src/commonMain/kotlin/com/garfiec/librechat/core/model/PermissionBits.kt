package com.garfiec.librechat.core.model

/**
 * Mirrors upstream `packages/data-provider/src/accessPermissions.ts` PermissionBits
 * enum. Treat the underlying [bits] as an opaque bitmask; never invent new bits.
 *
 * Server wire format is `Int`, so this is a value class around Int with named
 * helpers for the four bits the upstream server recognizes.
 */
@kotlin.jvm.JvmInline
value class PermissionBits(val bits: Int) {
    fun has(other: PermissionBits): Boolean = (bits and other.bits) == other.bits
    operator fun plus(other: PermissionBits): PermissionBits = PermissionBits(bits or other.bits)
    operator fun minus(other: PermissionBits): PermissionBits = PermissionBits(bits and other.bits.inv())

    companion object {
        val NONE = PermissionBits(0)

        /** 001 — Can view and use the resource */
        val VIEW = PermissionBits(1)

        /** 010 — Can modify the resource */
        val EDIT = PermissionBits(2)

        /** 100 — Can delete the resource */
        val DELETE = PermissionBits(4)

        /** 1000 — Can share the resource with others */
        val SHARE = PermissionBits(8)
    }
}

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

/**
 * Standard access role IDs (accessPermissions.ts:69-82). The upstream API
 * mutation endpoint accepts `accessRoleId` strings rather than raw bits, so this
 * is the on-wire string clients send when granting access.
 */
object AccessRoleIds {
    const val AGENT_VIEWER = "agent_viewer"
    const val AGENT_EDITOR = "agent_editor"
    const val AGENT_OWNER = "agent_owner"
    const val PROMPT_GROUP_VIEWER = "promptGroup_viewer"
    const val PROMPT_GROUP_EDITOR = "promptGroup_editor"
    const val PROMPT_GROUP_OWNER = "promptGroup_owner"
    const val MCP_SERVER_VIEWER = "mcpServer_viewer"
    const val MCP_SERVER_EDITOR = "mcpServer_editor"
    const val MCP_SERVER_OWNER = "mcpServer_owner"
    const val REMOTE_AGENT_VIEWER = "remoteAgent_viewer"
    const val REMOTE_AGENT_EDITOR = "remoteAgent_editor"
    const val REMOTE_AGENT_OWNER = "remoteAgent_owner"
    const val SKILL_VIEWER = "skill_viewer"
    const val SKILL_EDITOR = "skill_editor"
    const val SKILL_OWNER = "skill_owner"
}
