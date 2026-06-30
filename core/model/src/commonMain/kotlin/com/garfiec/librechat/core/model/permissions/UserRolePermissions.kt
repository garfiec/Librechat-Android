package com.garfiec.librechat.core.model.permissions

import kotlinx.serialization.Serializable

@Serializable
data class UserRolePermissions(
    val name: String,
    val description: String? = null,
    val permissions: Map<String, Map<String, Boolean>> = emptyMap(),
) {
    /**
     * Returns true if the permission is explicitly granted OR unknown.
     *
     * Permissive-default is intentional for forward compatibility: if a future server
     * adds a new PermissionType the client doesn't know about, or omits a field on an
     * older build, the client optimistically shows the UI. The server's middleware still
     * enforces via 403 — the client gate is for UX (hiding dead-end buttons), not security.
     *
     * This diverges from the PWA's `useHasAccess` (which returns false on unknown). The
     * rationale is mobile-specific: mobile apps are long-lived, so an old-client-on-new-server
     * scenario is more likely than on web. Permissive default avoids breaking existing users
     * when the server adds new permission types between releases.
     */
    fun hasAccess(type: PermissionType, permission: Permission): Boolean {
        val perms = permissions[type.serverKey] ?: return true
        val value = perms[permission.serverKey] ?: return true
        return value
    }

    /**
     * Fail-CLOSED variant: returns true ONLY when the permission is explicitly
     * granted. Unknown type/permission (or no permissions map) ⇒ false.
     *
     * Use this for MUTATION affordances (create/edit/delete/share) where showing
     * a button the server will 403 is worse UX than hiding it — the opposite of
     * [hasAccess]'s permissive default, which suits read/USE visibility. Skills
     * create/edit/delete gate on this (see scope-skills-builder.md §E gotcha 2).
     */
    fun hasAccessStrict(type: PermissionType, permission: Permission): Boolean {
        val perms = permissions[type.serverKey] ?: return false
        return perms[permission.serverKey] ?: false
    }
}

/**
 * Permissive-default variant of [UserRolePermissions.hasAccess] that treats a null role
 * (still loading, fetch failed, timed out) as permissive. Replaces the `role?.hasAccess(...) ?: true`
 * idiom so the permissive contract lives in one place instead of being open-coded at every
 * role-flag mapping site across the VMs.
 */
fun UserRolePermissions?.hasAccessOrPermissive(type: PermissionType, permission: Permission): Boolean =
    this?.hasAccess(type, permission) ?: true

/**
 * Fail-CLOSED variant for a nullable role: a null role (still loading / fetch
 * failed / timed out) is treated as DENIED. Pairs with
 * [UserRolePermissions.hasAccessStrict] for gating mutation affordances.
 */
fun UserRolePermissions?.hasAccessStrictOrDenied(type: PermissionType, permission: Permission): Boolean =
    this?.hasAccessStrict(type, permission) ?: false

/**
 * Share-conversation visibility gate (v0.8.7): the server feature flag AND the
 * SHARED_LINKS/CREATE permission. Permissive on unknown (via [hasAccessOrPermissive]) so older
 * backends that don't emit the permission keep showing Share; the server still enforces with 403.
 * Mirrors upstream ConvoOptions' `sharedLinksEnabled && canCreateSharedLinks`. Single source of
 * truth for both the drawer long-press menu and the chat overflow menu.
 */
fun UserRolePermissions?.canCreateSharedLinks(sharedLinksEnabled: Boolean): Boolean =
    sharedLinksEnabled && hasAccessOrPermissive(PermissionType.SHARED_LINKS, Permission.CREATE)
