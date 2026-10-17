package com.classmemory.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Original geometric icon artwork for Class Memory, defined directly as 24 × 24 Compose paths. */
object ClassMemoryIcons {
    private fun vector(name: String, vararg strokes: String): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        strokes.forEach { path ->
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    val Mic by lazy { vector("Mic", "M10 5a2 2 0 0 1 4 0v7a2 2 0 0 1 -4 0z", "M6 11a6 6 0 0 0 12 0", "M12 17v4", "M8 21h8") }
    val Camera by lazy { vector("Camera", "M3 8h4l2 -3h6l2 3h4v11H3z", "M12 10a3 3 0 1 0 0 6a3 3 0 1 0 0 -6z", "M6 11h.1") }
    val Stop by lazy { vector("Stop", "M6 6h12v12H6z") }
    val History by lazy { vector("History", "M5 8a8 8 0 1 1 -1 5", "M4 4v4h4", "M12 7v5l3 2") }
    val Settings by lazy { vector("Settings", "M12 3v3", "M12 18v3", "M3 12h3", "M18 12h3", "M5.6 5.6l2.1 2.1", "M16.3 16.3l2.1 2.1", "M18.4 5.6l-2.1 2.1", "M7.7 16.3l-2.1 2.1", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0 -8z") }
    val Edit by lazy { vector("Edit", "M4 17l10 -10 3 3 -10 10H4z", "M12 9l3 3", "M17 7l2 -2 2 2 -2 2") }
    val Trash by lazy { vector("Trash", "M5 7h14", "M8 7V5h8v2", "M7 7l1 14h8l1 -14", "M10 11v6", "M14 11v6") }
    val Share by lazy { vector("Share", "M12 15V3", "M8 7l4 -4 4 4", "M5 12v8h14v-8") }
    val File by lazy { vector("File", "M6 3h8l4 4v14H6z", "M14 3v5h5", "M9 13h6", "M9 17h6") }
    val Notebook by lazy { vector("Notebook", "M6 3h13v18H6z", "M9 3v18", "M12 8h4", "M12 12h4", "M12 16h4") }
    val Check by lazy { vector("Check", "M5 12l4 4 10 -9") }
    val Warning by lazy { vector("Warning", "M12 3L3 20h18L12 3z", "M12 9v5", "M12 17v.1") }
    val Close by lazy { vector("Close", "M6 6l12 12", "M18 6L6 18") }
    val Back by lazy { vector("Back", "M15 6l-6 6 6 6", "M9 12h10") }
    val More by lazy { vector("More", "M5 12h.1", "M12 12h.1", "M19 12h.1") }
    val Photo by lazy { vector("Photo", "M3 4h18v16H3z", "M7 8a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0 -3z", "M4 18l5 -5 3 3 3 -5 5 7") }
}
