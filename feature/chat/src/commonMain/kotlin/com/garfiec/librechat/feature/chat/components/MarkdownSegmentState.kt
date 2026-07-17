package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Parses [text] into [MarkdownSegment]s. While [streaming], parsing runs off the main thread with
 * the previous result retained (and the first text parsed synchronously) so the bubble never blanks.
 */
@Composable
internal fun rememberMarkdownSegments(
    text: String,
    streaming: Boolean,
): List<MarkdownSegment> {
    if (!streaming) {
        return remember(text) { parseMarkdownSegments(text) }
    }

    val latestText by rememberUpdatedState(text)
    var segments by remember { mutableStateOf(parseMarkdownSegments(text)) }
    val initialText = remember { text }

    LaunchedEffect(Unit) {
        collectMarkdownSegments(
            texts = snapshotFlow { latestText },
            alreadyParsed = initialText,
        ) { segments = it }
    }

    return segments
}

/**
 * Parses the latest text from [texts] on [parseContext]. [parseContext] must differ from the
 * caller's dispatcher, or a cancelled parse publishes its result instead of being discarded.
 */
internal suspend fun collectMarkdownSegments(
    texts: Flow<String>,
    alreadyParsed: String,
    parse: (String) -> List<MarkdownSegment> = ::parseMarkdownSegments,
    parseContext: CoroutineContext = Dispatchers.Default,
    onSegments: (List<MarkdownSegment>) -> Unit,
) {
    var lastParsed = alreadyParsed
    texts.conflate().collect { t ->
        if (t == lastParsed) return@collect
        val parsed = withContext(parseContext) { parse(t) }
        onSegments(parsed)
        lastParsed = t
    }
}
