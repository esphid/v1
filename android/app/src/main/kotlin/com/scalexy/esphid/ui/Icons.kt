package com.scalexy.esphid.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Small stroke icon set drawn to match the device console (no icon library dependency). */
object Ic {
    private fun icon(name: String, strokes: List<String> = emptyList(), fills: List<String> = emptyList(), width: Float = 1.8f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in fills) b.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        for (d in strokes)
            b.addPath(
                pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        return b.build()
    }

    val Add = icon("add", listOf("M12 5v14M5 12h14"))
    val Back = icon("back", listOf("M15 5l-7 7 7 7"))
    val Close = icon("close", listOf("M6 6l12 12M18 6L6 18"))
    val Check = icon("check", listOf("M5 12.5l4.5 4.5L19 7"))
    val Sliders = icon("sliders", listOf("M4 7h9M18 7h2M4 17h3M12 17h8", "M15.5 7m-2.5 0a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0", "M9.5 17m-2.5 0a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"))
    val Keyboard = icon("keyboard", listOf("M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M6 10h0.01M10 10h0.01M14 10h0.01M18 10h0.01M7.5 14h9"))
    val Command = icon("command", listOf("M9 9V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V9z"))
    val Wifi = icon("wifi", listOf("M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0"), listOf("M12 18.4a0.9 0.9 0 1 0 0 1.8a0.9 0.9 0 1 0 0-1.8z"))
    val Bluetooth = icon("bluetooth", listOf("M7 7l10 10-5 5V2l5 5L7 17"))
    val Globe = icon("globe", listOf("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM3 12h18M12 3c2.5 2.5 3.8 5.5 3.8 9s-1.3 6.5-3.8 9c-2.5-2.5-3.8-5.5-3.8-9s1.3-6.5 3.8-9z"))
    val Nodes = icon("nodes", listOf("M6 18a2 2 0 1 0 0-4a2 2 0 1 0 0 4zM18 10a2 2 0 1 0 0-4a2 2 0 1 0 0 4zM18 20a2 2 0 1 0 0-4a2 2 0 1 0 0 4zM8 15.2l8-6M8 16.6l8 1.8"))
    val Refresh = icon("refresh", listOf("M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"))
    val Lock = icon("lock", listOf("M5 11h14v9H5zM8 11V8a4 4 0 0 1 8 0v3"))
    val Trash = icon("trash", listOf("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"))
    val ChevronDown = icon("chevron-down", listOf("M6 9l6 6 6-6"))
    val ChevronRight = icon("chevron-right", listOf("M9 6l6 6-6 6"))
    val Chip = icon("chip", listOf("M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"))
    val Prev = icon("prev", fills = listOf("M6 5h2v14H6zM20 5v14L9 12z"))
    val PlayPause = icon("playpause", fills = listOf("M4 5v14l9-7zM15 5h2v14h-2zM19 5h2v14h-2z"))
    val Next = icon("next", fills = listOf("M16 5h2v14h-2zM4 5v14l11-7z"))
    private const val SPEAKER = "M4 9.5v5h3.5L12 18V6L7.5 9.5z"
    val VolDown = icon("vol-down", listOf("M16 12h5"), listOf(SPEAKER))
    val Mute = icon("mute", listOf("M16 9.5l5 5M21 9.5l-5 5"), listOf(SPEAKER))
    val VolUp = icon("vol-up", listOf("M16 12h5M18.5 9.5v5"), listOf(SPEAKER))
    val SunDim = icon("sun-dim", listOf("M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM12 4v1.5M12 18.5V20M6.3 6.3l1 1M16.7 16.7l1 1M4 12h1.5M18.5 12H20M6.3 17.7l1-1M16.7 7.3l1-1"))
    val SunBright = icon("sun-bright", listOf("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 2v2.5M12 19.5V22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M2 12h2.5M19.5 12H22M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"))
}
