package app.wraith.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Wraith's palette: a dark base, translucent "glass" surfaces and one accent colour. */
@Immutable
data class WraithColors(
    val bg: Color, val glass: Color, val glassHigh: Color, val stroke: Color,
    val text: Color, val muted: Color, val faint: Color,
    val accent: Color, val onAccent: Color, val accentSoft: Color,
    val good: Color, val warn: Color, val bad: Color,
)

val LocalWraith = staticCompositionLocalOf { palette(Color(0xFFF2A7D8), false) }
val W: WraithColors @Composable get() = LocalWraith.current

/** Accent presets; "photo" and "system" are resolved at runtime. */
val ACCENTS = linkedMapOf(
    "sakura" to Color(0xFFF2A7D8), "lavender" to Color(0xFFB8A6FF), "mint" to Color(0xFF8BE6C6),
    "ice" to Color(0xFF8FD0FF), "amber" to Color(0xFFFFC978), "coral" to Color(0xFFFF9E8A),
)

fun palette(accent: Color, amoled: Boolean): WraithColors {
    // Keep any accent readable on the dark base: very dark picks are lifted towards white.
    val a = if (accent.luminance() < 0.25f) lerp(accent, Color.White, 0.45f) else accent
    val on = if (a.luminance() > 0.45f) lerp(a, Color.Black, 0.82f) else Color.White
    return WraithColors(
        bg = if (amoled) Color.Black else Color(0xFF0B0A10),
        glass = Color(0xA6141219), glassHigh = Color(0xCC1E1B26), stroke = Color(0x1AFFFFFF),
        text = Color(0xFFF5F2FA), muted = Color(0xFFAAA5B8), faint = Color(0xFF6E6A7A),
        accent = a, onAccent = on, accentSoft = a.copy(alpha = 0.18f),
        good = Color(0xFF7BE0A6), warn = Color(0xFFFFCF7A), bad = Color(0xFFFF8F8F),
    )
}

@Composable
fun WraithTheme(colors: WraithColors, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, primaryContainer = colors.accentSoft, onPrimaryContainer = colors.text,
        secondary = colors.accent, onSecondary = colors.onAccent, secondaryContainer = Color(0xFF2A2633), onSecondaryContainer = colors.text,
        tertiary = colors.accent, background = colors.bg, onBackground = colors.text,
        surface = Color(0xFF16141C), onSurface = colors.text, surfaceVariant = Color(0xFF221F2A), onSurfaceVariant = colors.muted,
        surfaceContainerLowest = colors.bg, surfaceContainerLow = Color(0xFF131119), surfaceContainer = Color(0xFF18161F),
        surfaceContainerHigh = Color(0xFF1E1B26), surfaceContainerHighest = Color(0xFF26222F),
        outline = Color(0xFF4A4655), outlineVariant = Color(0xFF2E2A36), error = colors.bad, onError = Color.Black,
        errorContainer = Color(0xFF4A1F24), onErrorContainer = Color(0xFFFFDAD6), inverseSurface = colors.text, inverseOnSurface = colors.bg,
        scrim = Color.Black,
    )
    val base = FontFamily.Default
    val type = Typography(
        displayLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 46.sp, lineHeight = 50.sp, letterSpacing = (-1.2).sp),
        headlineMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
        titleLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.2).sp),
        titleMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = TextStyle(fontFamily = base, fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = base, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = base, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    )
    CompositionLocalProvider(LocalWraith provides colors) { MaterialTheme(colorScheme = scheme, typography = type, content = content) }
}

/** Line icons on a 24×24 grid, the same visual language across the app. */
object Ic {
    private fun icon(name: String, path: String, stroke: Float = 1.8f): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), stroke = SolidColor(Color.White), strokeLineWidth = stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        .build()

    val Home = icon("home", "M3.5 10.5 12 3.5l8.5 7M5.5 9v11h4.5v-6h4v6h4.5V9")
    val Globe = icon("globe", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M3.5 9h17M3.5 15h17M12 3c-3 3.2-3 14.8 0 18M12 3c3 3.2 3 14.8 0 18")
    val Bars = icon("bars", "M5.5 20v-7M12 20V5M18.5 20v-10", 2.2f)
    val Settings = icon("settings", "M4 7h8M17 7h3M4 17h3M12 17h8M14.5 4.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5M9.5 14.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5")
    val Power = icon("power", "M7.2 6.5a8 8 0 1 0 9.6 0M12 3v8.5", 2.2f)
    val Plus = icon("plus", "M5 12h14M12 5v14", 2f)
    val Close = icon("close", "M6 6l12 12M18 6 6 18", 2f)
    val Back = icon("back", "M19 12H5M11 6l-6 6 6 6", 2f)
    val Chevron = icon("chevron", "M9.5 6l6 6-6 6", 2f)
    val ChevronDown = icon("chevrondown", "M6 9.5l6 6 6-6", 2f)
    val More = icon("more", "M12 5h.01M12 12h.01M12 19h.01", 3f)
    val Refresh = icon("refresh", "M20 12a8 8 0 1 1-2.4-5.7M20 4v5h-5")
    val Search = icon("search", "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13M15.5 15.5l5 5")
    val Star = icon("star", "M12 3.5l2.6 5.4 5.9.7-4.3 4.1 1.1 5.8L12 16.7l-5.3 2.8 1.1-5.8-4.3-4.1 5.9-.7z")
    val Pulse = icon("pulse", "M2.5 12h4l3-7 5 14 3-7h4")
    val Qr = icon("qr", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2.5M14 14v2.5M18 18h2M20 14v.01M16.5 20h.01M14 20h.01")
    val Copy = icon("copy", "M9 9h11v11H9zM15 9V4H4v11h5")
    val Edit = icon("edit", "M4 20v-4L15.5 4.5l4 4L8 20zM13.5 6.5l4 4")
    val Delete = icon("delete", "M4 6.5h16M6.5 6.5l1 14h9l1-14M9.5 6.5l1-3h3l1 3")
    val Shield = icon("shield", "M12 2.8l8 3.4-.8 7.4C18 18 15 20.4 12 21.4 9 20.4 6 18 4.8 13.6L4 6.2zM8.8 12l2.2 2.2 4.4-4.6")
    val Route = icon("route", "M6 3.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4M18 16.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4M6 7.5v6a5 5 0 0 0 5 5h5M18 16.5V12a3 3 0 0 0-3-3H6")
    val Apps = icon("apps", "M5 5h4v4H5zM15 5h4v4h-4zM5 15h4v4H5zM15 15h4v4h-4z")
    val Image = icon("image", "M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15.5 9h.01")
    val Palette = icon("palette", "M12 3a9 9 0 1 0 0 18c1.5 0 2-1 2-2s-1-1.6-1-2.6c0-1 .8-1.9 2-1.9h2a4 4 0 0 0 4-4C21 6.5 17 3 12 3M7.5 11.5h.01M10 7.5h.01M15 7.5h.01", 1.8f)
    val Link = icon("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val Code = icon("code", "M9 7l-5 5 5 5M15 7l5 5-5 5")
    val Info = icon("info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 11v5.5M12 7.5h.01")
    val Download = icon("download", "M12 4v11M7 10l5 5 5-5M5 20h14")
    val Up = icon("up", "M12 19V5M6 11l6-6 6 6", 2f)
    val Down = icon("down", "M12 5v14M6 13l6 6 6-6", 2f)
    val Clock = icon("clock", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 7.5V12l3 2")
    val Log = icon("log", "M5 3h14v18H5zM9 8h6M9 12h6M9 16h3")
    val Bolt = icon("bolt", "M13 2.5 4.5 13.5h6.5l-1 8 8.5-11h-6.5z")
    val Check = icon("check", "M5 12.5l4.5 4.5L19.5 7", 2.2f)
    val User = icon("user", "M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8M4.5 20.5c1-3.8 3.8-5.8 7.5-5.8s6.5 2 7.5 5.8")
    val Phone = icon("phone", "M7.5 2.5h9v19h-9zM11 18h2")
    val Lock = icon("lock", "M6 10.5h12v10H6zM8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3")
    val Dns = icon("dns", "M4 4h16v6H4zM4 14h16v6H4zM7.5 7h.01M7.5 17h.01")
    val Layers = icon("layers", "M12 3 3 8l9 5 9-5zM3 13l9 5 9-5")
    val Sort = icon("sort", "M4 7h16M7 12h10M10 17h4")
    val Wifi = icon("wifi", "M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.8 16a5 5 0 0 1 6.4 0M12 19.5h.01")
    val Eye = icon("eye", "M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12M12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5")
    val Bell = icon("bell", "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15zM10 20.5h4")
    val Sparkle = icon("sparkle", "M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6")
}
