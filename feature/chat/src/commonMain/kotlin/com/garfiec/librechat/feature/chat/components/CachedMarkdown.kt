package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * Renders markdown via the m3 [Markdown] composable, backed by the session-wide
 * [LocalParsedMarkdownCache] of parsed ASTs.
 *
 * Two paths, chosen by [streaming]:
 *
 * **Settled content** ([streaming] = false — finished messages, inline
 * artifacts). The content is stable, so a valid cache hit is rendered directly
 * via `Markdown(state = cached)` — no [com.mikepenz.markdown.model.MarkdownState]
 * is built and no parse runs (not even the synchronous `parseBlocking` that
 * [immediate] would trigger). This is the LazyColumn re-entry path: scrolling a
 * message off-screen and back renders instantly from the cache with zero CPU.
 * Because the content won't change underneath us there is no branch-swap flash.
 * On a cache miss (first display, or after LRU eviction) it falls through to the
 * live path below and caches the result.
 *
 * **Streaming content** ([streaming] = true — the live reply bubble). Content
 * changes every delta, so a cache-hit fast path would thrash: an earlier version
 * branched between `Markdown(state = cached)` and a freshly-remembered live
 * state, and the post-parse cache write flipped the branch every token, each
 * flip re-creating a [com.mikepenz.markdown.model.MarkdownState] that started at
 * [State.Loading] (a 0-px slot) — a visible flash per delta. Instead we keep a
 * single persistent state with `retainState = true`, so a content change does
 * NOT reset the flow to Loading; the previous [State.Success] stays on screen
 * until the next parse lands and the text never blanks between tokens. Streaming
 * also does NOT write to the cache: a long reply would otherwise stuff hundreds
 * of partial-content entries into the shared LRU and evict other messages'
 * settled ASTs. The terminal content is cached once the reply renders as settled.
 *
 * Padding/dimens/etc are left at library defaults — callers that need to
 * customize those should fall back to invoking [Markdown] directly.
 *
 * [immediate] parses the *initial* content synchronously (no Loading frame on
 * first paint) — used for fully-settled artifact content. It only affects the
 * live path's first parse; it is not involved in the streaming flash fix, which
 * relies on the persistent-state + `retainState` behavior.
 */
@Composable
fun CachedMarkdown(
    content: String,
    colors: MarkdownColors,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier,
    immediate: Boolean = false,
    streaming: Boolean = false,
) {
    val cache = LocalParsedMarkdownCache.current
    val key = parsedMarkdownCacheKey(content)
    // The last message opts into synchronous first-parse; see [LocalImmediateMarkdown].
    val immediate = immediate || LocalImmediateMarkdown.current

    if (!streaming) {
        // Fast path: stable content + a cache hit → render the cached AST with
        // no MarkdownState and no parse. The content equality guard (not just
        // the 32-bit hash key) keeps a hash collision on the shared cache from
        // rendering a different message's AST.
        cache[key]?.takeIf { it.content == content }?.let { cached ->
            Markdown(
                state = cached,
                colors = colors,
                typography = typography,
                modifier = modifier,
            )
            return
        }
    }

    // Streaming, or a settled cache miss (first render / post-eviction).
    val mdState = rememberMarkdownState(
        content = content,
        immediate = immediate,
        retainState = true,
    )
    val liveState by mdState.state.collectAsState()
    val liveSuccess = liveState as? State.Success

    // Cache each parsed AST keyed by its own content (not the current `content`,
    // which may be a step ahead while retainState holds the previous Success).
    // Streaming deltas are intentionally not cached — see the kdoc.
    LaunchedEffect(liveSuccess, streaming) {
        if (!streaming) {
            liveSuccess?.let { cache.put(parsedMarkdownCacheKey(it.content), it) }
        }
    }

    // Render priority:
    //  1. the live parse for *this* content (streaming + settled steady state),
    //  2. a cached AST for this content (settled re-entry before the parse lands),
    //  3. whatever the live state is — only the very first paint of brand-new,
    //     uncached content renders Loading.
    // Both cache-bearing tiers verify the Success's own `content` matches exactly,
    // not just its hash key, so a 32-bit hashCode collision can't render another
    // message's AST. retainState also lets `liveSuccess` lag a delta behind
    // `content` while streaming, so tier 1 must reject the stale one.
    val effective = liveSuccess?.takeIf { it.content == content }
        ?: cache[key]?.takeIf { it.content == content }
        ?: liveState

    Markdown(
        state = effective,
        colors = colors,
        typography = typography,
        modifier = modifier,
    )
}
