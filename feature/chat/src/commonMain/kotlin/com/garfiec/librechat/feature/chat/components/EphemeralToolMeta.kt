package com.garfiec.librechat.feature.chat.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.tool_code
import com.garfiec.librechat.feature.chat.resources.tool_code_desc
import com.garfiec.librechat.feature.chat.resources.tool_file_search
import com.garfiec.librechat.feature.chat.resources.tool_file_search_desc
import com.garfiec.librechat.feature.chat.resources.tool_url_context
import com.garfiec.librechat.feature.chat.resources.tool_url_context_desc
import com.garfiec.librechat.feature.chat.resources.tool_web_search
import com.garfiec.librechat.feature.chat.resources.tool_web_search_desc
import org.jetbrains.compose.resources.StringResource

/**
 * Single source of truth for the icon + label + description of the ephemeral chat tools,
 * shared by the tools bottom sheet ([ChatToolsSheetContent]) and the input-bar pinned-tool
 * chips ([PinnedToolsRow]) so the two surfaces can't drift. Returns null for keys mobile
 * doesn't surface as a toggle (e.g. `artifacts`, `mcp`, MCP server names).
 */
internal data class EphemeralToolMeta(
    val icon: ImageVector,
    val titleRes: StringResource,
    val descriptionRes: StringResource,
)

internal fun ephemeralToolMeta(toolKey: String): EphemeralToolMeta? = when (toolKey) {
    ToolConstants.WEB_SEARCH ->
        EphemeralToolMeta(Icons.Default.Search, Res.string.tool_web_search, Res.string.tool_web_search_desc)
    ToolConstants.URL_CONTEXT ->
        EphemeralToolMeta(Icons.Default.Link, Res.string.tool_url_context, Res.string.tool_url_context_desc)
    ToolConstants.FILE_SEARCH ->
        EphemeralToolMeta(Icons.Default.FindInPage, Res.string.tool_file_search, Res.string.tool_file_search_desc)
    ToolConstants.CODE_INTERPRETER ->
        EphemeralToolMeta(Icons.Default.Code, Res.string.tool_code, Res.string.tool_code_desc)
    else -> null
}
