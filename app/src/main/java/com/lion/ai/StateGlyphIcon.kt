package com.lion.ai

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** أيقونة متجهية خطّية لكل حالة تقنية (تفكير، كتابة، ملف، بناء...). */
internal fun glyphVector(glyph: StateGlyph): ImageVector = when (glyph) {
    StateGlyph.THINKING -> Icons.Outlined.AutoAwesome
    StateGlyph.WRITING -> Icons.Outlined.Edit
    StateGlyph.FILE -> Icons.Outlined.Description
    StateGlyph.SEARCH -> Icons.Outlined.Search
    StateGlyph.BUILD -> Icons.Outlined.Build
    StateGlyph.UPLOAD -> Icons.Outlined.CloudUpload
    StateGlyph.TERMINAL -> Icons.Outlined.Terminal
    StateGlyph.SUCCESS -> Icons.Outlined.CheckCircle
    StateGlyph.ERROR -> Icons.Outlined.ErrorOutline
    StateGlyph.WARNING -> Icons.Outlined.WarningAmber
    StateGlyph.WAITING -> Icons.Outlined.HourglassTop
    StateGlyph.SWITCH -> Icons.Outlined.SwapHoriz
    StateGlyph.SYNC -> Icons.Outlined.Sync
    StateGlyph.ASK -> Icons.Outlined.ChatBubbleOutline
    StateGlyph.WEB -> Icons.Outlined.Public
    StateGlyph.LIVE -> Icons.Outlined.Sensors
    StateGlyph.GENERIC -> Icons.Outlined.Bolt
}

@Composable
internal fun StateGlyphIcon(
    glyph: StateGlyph,
    tint: Color,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = glyphVector(glyph),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size)
    )
}
