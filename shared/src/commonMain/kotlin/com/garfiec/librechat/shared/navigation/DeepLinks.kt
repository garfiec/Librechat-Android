package com.garfiec.librechat.shared.navigation

import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.chat.navigation.ArtifactShortcutViewer
import com.garfiec.librechat.feature.chat.navigation.Chat
import com.garfiec.librechat.feature.chat.navigation.NewChat

/**
 * Platform-neutral view of an incoming `librechat://` URI, so [DeepLinks.resolve] — the routing
 * decision — lives in common and can be reused per platform. Android builds this from
 * `android.net.Uri` (see `DeepLinkUriAdapter`); an iOS intake (from `NSURLComponents`) would build
 * the same shape and reuse [DeepLinks.resolve] — not yet wired. Only field extraction is per-platform.
 */
data class DeepLinkUri(
    val host: String?,
    val pathSegments: List<String>,
    val query: Map<String, String>,
)

/**
 * The outcome of resolving a deep link. A sealed result (not a bare `NavKey?`) so the three
 * genuinely different link kinds are each expressible: navigate, accept-but-don't-navigate, ignore.
 */
sealed interface DeepLinkResolution {
    /**
     * Navigate to [target]. [requiresAuth] links redirect to login when logged out (a conversation
     * or a model-preselect chat is useless without a session); non-auth links open even logged out
     * (a device-scoped artifact snapshot renders from local Room).
     */
    data class Route(val target: NavKey, val requiresAuth: Boolean) : DeepLinkResolution

    /**
     * Accepted — the app should come to the foreground — but there is nothing to place on the back
     * stack: the payload is consumed by an in-progress flow. The OAuth redirect is the case; its
     * refresh token rides back in a cookie read by the login screen (`checkOAuthResult`), so the
     * link only needs to return focus to the app.
     */
    data object Consumed : DeepLinkResolution

    /** Not a link this app routes: wrong host, or a malformed / failed-validation id. */
    data object None : DeepLinkResolution
}

/**
 * Single source of truth for `librechat://` deep-link routing. Every host is declared here exactly
 * once; both platform intake (the accept / bring-to-front decision) and the nav host (back-stack
 * placement) call [resolve], so there is no second allowlist to drift out of sync.
 *
 * To add a deep-linked feature, add one [resolve] branch. Path ids validate through a [Regex];
 * query-param links read [DeepLinkUri.query]. Treat all extracted values as untrusted input — match
 * them against a strict pattern (ids) or pass them as opaque strings (never interpret as commands).
 */
object DeepLinks {
    const val SCHEME = "librechat"

    /**
     * Both a LibreChat conversationId and an artifact-shortcut snapshot id are UUIDs — the server
     * generates conversationId via uuidv4() (validated server-side with isUUID), and the snapshot id
     * is Uuid.random(). Validate strictly so a malformed segment resolves to [DeepLinkResolution.None]
     * rather than routing to a dead screen. (Not a Mongo ObjectID — that would reject every real id.)
     */
    private val UUID_PATTERN = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    fun resolve(uri: DeepLinkUri): DeepLinkResolution = when (uri.host) {
        "conversation" -> uri.pathSegments.firstOrNull()
            ?.takeIf { UUID_PATTERN.matches(it) }
            ?.let { DeepLinkResolution.Route(Chat(it), requiresAuth = true) }
            ?: DeepLinkResolution.None

        "artifact" -> uri.pathSegments.firstOrNull()
            ?.takeIf { UUID_PATTERN.matches(it) }
            ?.let { DeepLinkResolution.Route(ArtifactShortcutViewer(it), requiresAuth = false) }
            ?: DeepLinkResolution.None

        // Home-screen model shortcut (librechat://model?endpoint=<endpoint>&model=<model>): the payload
        // rides on the NewChat route so it replaces a bare landing NewChat (dedup-by-value) and re-seeds
        // even when already on the landing. Auth-required — a model preselect is useless without a session.
        "model" -> {
            val endpoint = uri.query["endpoint"]
            val model = uri.query["model"]
            if (!endpoint.isNullOrBlank() && !model.isNullOrBlank()) {
                DeepLinkResolution.Route(NewChat(endpoint = endpoint, model = model), requiresAuth = true)
            } else {
                DeepLinkResolution.None
            }
        }

        // OAuth redirect (librechat://oauth): token comes back via cookie, not the URI — see [Consumed].
        "oauth" -> DeepLinkResolution.Consumed

        else -> DeepLinkResolution.None
    }
}
