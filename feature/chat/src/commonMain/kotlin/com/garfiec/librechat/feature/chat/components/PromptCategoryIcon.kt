package com.garfiec.librechat.feature.chat.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps a prompt group's category to an icon, mirroring the web client's category set so the same
 * prompt is recognisable on both. Unknown or absent categories fall back to the generic box the web
 * client uses for `misc`, rather than rendering nothing and leaving the rows misaligned.
 */
fun promptCategoryIcon(category: String?): ImageVector = when (category?.lowercase()) {
    "roleplay" -> Icons.Default.Casino
    "write" -> Icons.Default.Edit
    "idea" -> Icons.Default.Lightbulb
    "shop" -> Icons.Default.ShoppingBag
    "finance", "sales" -> Icons.AutoMirrored.Filled.ShowChart
    "code", "it" -> Icons.Default.Code
    "travel" -> Icons.Default.Flight
    "teach_or_explain" -> Icons.Default.School
    "hr" -> Icons.Default.Group
    "rd" -> Icons.Default.Science
    "aftersales" -> Icons.Default.Settings
    else -> Icons.Default.Inventory2
}
