package com.garfiec.librechat.core.model

import kotlinx.serialization.Serializable

/**
 * Web-search sources carried on an assistant message. The server attaches these as an
 * attachment whose `type == "web_search"`, with this payload nested under the `web_search`
 * key (see upstream `api/server/services/Tools/search.js` `buildAttachment`). Unlike file
 * attachments it has no `file_id`/`filename` — the sources themselves are the payload.
 *
 * Mirrors the server's `SearchResultData` (`packages/data-provider/src/types/web.ts`); we keep
 * only the fields the mobile UI renders — `organic` results and `topStories`.
 */
@Serializable
data class WebSearchData(
    val turn: Int? = null,
    val organic: List<WebSearchSource>? = null,
    val topStories: List<WebSearchSource>? = null,
)

/** A single web-search source (an organic result or a top story). */
@Serializable
data class WebSearchSource(
    val link: String? = null,
    val title: String? = null,
    val snippet: String? = null,
    /** Publisher name — present on `topStories`, absent on `organic` results. */
    val source: String? = null,
    val date: String? = null,
)
