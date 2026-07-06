package com.garfiec.librechat.navigation

import android.net.Uri
import com.garfiec.librechat.shared.navigation.DeepLinkUri

/** Adapts an Android [Uri] to the platform-neutral [DeepLinkUri] the shared resolver consumes. */
fun Uri.toDeepLinkUri(): DeepLinkUri = DeepLinkUri(
    host = host,
    pathSegments = pathSegments.orEmpty(),
    // The query accessors throw UnsupportedOperationException on an opaque ("librechat:foo", no "//")
    // URI, which the scheme-only intent filter still delivers. Such URIs carry no routable host, so
    // give them an empty query rather than crashing; the resolver drops them as None.
    query = if (isHierarchical) {
        queryParameterNames.orEmpty().associateWith { getQueryParameter(it).orEmpty() }
    } else {
        emptyMap()
    },
)
