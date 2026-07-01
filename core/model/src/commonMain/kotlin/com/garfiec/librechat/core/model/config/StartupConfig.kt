package com.garfiec.librechat.core.model.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class StartupConfig(
    val appTitle: String = "LibreChat",
    val emailLoginEnabled: Boolean = true,
    val registrationEnabled: Boolean = false,
    val socialLoginEnabled: Boolean = false,
    val socialLogins: List<String>? = null,
    val googleLoginEnabled: Boolean = false,
    val githubLoginEnabled: Boolean = false,
    val discordLoginEnabled: Boolean = false,
    val facebookLoginEnabled: Boolean = false,
    val appleLoginEnabled: Boolean = false,
    val openidLoginEnabled: Boolean = false,
    val openidLabel: String? = null,
    val openidImageUrl: String? = null,
    val openidAutoRedirect: Boolean = false,
    val samlLoginEnabled: Boolean = false,
    val samlLabel: String? = null,
    val samlImageUrl: String? = null,
    val emailEnabled: Boolean = true,
    val passwordResetEnabled: Boolean = false,
    val publicSharedLinksEnabled: Boolean = false,
    val sharedLinksEnabled: Boolean = false,
    val serverDomain: String = "",
    val helpAndFaqURL: String? = null,
    val minPasswordLength: Int? = null,
    val showBirthdayIcon: Boolean = false,
    val modelSpecs: ModelSpecs? = null,
    @SerialName("interface") val interfaceConfig: InterfaceConfig? = null,
    val turnstile: TurnstileConfig? = null,
    val balance: BalanceConfig? = null,
    val analyticsGtmId: String? = null,
    val allowAccountDeletion: Boolean = true,
    val bundlerURL: String? = null,
    val staticBundlerURL: String? = null,
    val sharePointFilePickerEnabled: Boolean? = null,
    val sharePointBaseUrl: String? = null,
    val sharePointPickerGraphScope: String? = null,
    val sharePointPickerSharePointScope: String? = null,
    val openidReuseTokens: Boolean? = null,
    val conversationImportMaxFileSize: Long? = null,
    val webSearch: JsonObject? = null,
    val ldap: LdapConfig? = null,
    /** Backend version, if the server includes it in the config response (not yet standard). */
    val version: String? = null,
    // --- v0.8.6 detection (parse-surface only) ---
    /** Real-user-monitoring config (server-side telemetry). Opaque to mobile; kept for parse coverage. */
    val rum: JsonObject? = null,
    /** CloudFront signed-cookie refresh config. Mobile downloads directly, so opaque/unused. */
    val cloudFront: JsonObject? = null,
    /** Server build metadata (commit/branch/buildDate), gated by `interface.buildInfo`. */
    val buildInfo: BuildInfo? = null,
    // --- v0.8.7 detection (parse-surface only) ---
    /** Whether shared links may include snapshot file copies. Mirrors `interface.sharedLinks.snapshotFiles`
     *  at startup level. Mobile has no public-share viewer / snapshot fetch, so detection-only. */
    val sharedLinksSnapshotFilesEnabled: Boolean? = null,
)
