package com.jarves.mh.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val PocketOrange = Color(0xFFF28C52)
val PocketBlue = Color(0xFF8EA8FF)
val PocketGreen = Color(0xFF69D69E)
val PocketBackground = Color(0xFF0B0E14)
val PocketSurface = Color(0xFF131821)
val PocketSurfaceVariant = Color(0xFF1B222D)
val PocketOutline = Color(0xFF2A3240)

private val ForgeDark = darkColorScheme(
    primary = PocketOrange,
    onPrimary = Color(0xFF241107),
    primaryContainer = Color(0xFF42281D),
    onPrimaryContainer = Color(0xFFFFDDCC),
    secondary = PocketBlue,
    onSecondary = Color(0xFF001F58),
    tertiary = PocketGreen,
    onTertiary = Color(0xFF00391E),
    background = PocketBackground,
    onBackground = Color(0xFFE6EDF3),
    surface = PocketSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = PocketSurfaceVariant,
    onSurfaceVariant = Color(0xFF9AA0A6),
    outline = PocketOutline,
    outlineVariant = Color(0xFF333B4A),
)

private val ForgeLight = lightColorScheme(
    primary = Color(0xFFD85A20),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE0D2),
    onPrimaryContainer = Color(0xFF451A08),
    secondary = Color(0xFF3366CC),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF1B8A5A),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEAEFF5),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFFD0D7DE),
    outlineVariant = Color(0xFFD8DEE4),
)

// VS Code "Dark+" / "Light+"
private val DarkPlusDark = darkColorScheme(
    primary = Color(0xFF007ACC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF094771),
    onPrimaryContainer = Color(0xFFCFE7FD),
    secondary = Color(0xFF4EC9B0),
    onSecondary = Color(0xFF003229),
    tertiary = Color(0xFF89D185),
    onTertiary = Color(0xFF0B2A0D),
    background = Color(0xFF1E1E1E),
    onBackground = Color(0xFFD4D4D4),
    surface = Color(0xFF252526),
    onSurface = Color(0xFFD4D4D4),
    surfaceVariant = Color(0xFF2D2D30),
    onSurfaceVariant = Color(0xFFA1A1A1),
    outline = Color(0xFF3C3C3C),
    outlineVariant = Color(0xFF333333),
)

private val DarkPlusLight = lightColorScheme(
    primary = Color(0xFF005FB8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6EBFF),
    onPrimaryContainer = Color(0xFF04355F),
    secondary = Color(0xFF267F99),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF388A34),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF3B3B3B),
    surface = Color(0xFFF3F3F3),
    onSurface = Color(0xFF3B3B3B),
    surfaceVariant = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFF616161),
    outline = Color(0xFFCECECE),
    outlineVariant = Color(0xFFE5E5E5),
)

// Monokai
private val MonokaiDark = darkColorScheme(
    primary = Color(0xFFF92672),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4E1B2B),
    onPrimaryContainer = Color(0xFFFFD3E2),
    secondary = Color(0xFFE6DB74),
    onSecondary = Color(0xFF272822),
    tertiary = Color(0xFFA6E22E),
    onTertiary = Color(0xFF1C250B),
    background = Color(0xFF272822),
    onBackground = Color(0xFFF8F8F2),
    surface = Color(0xFF2D2E27),
    onSurface = Color(0xFFF8F8F2),
    surfaceVariant = Color(0xFF3E3D32),
    onSurfaceVariant = Color(0xFFA5A38F),
    outline = Color(0xFF49483E),
    outlineVariant = Color(0xFF3B3A32),
)

private val MonokaiLight = lightColorScheme(
    primary = Color(0xFFC2185B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E4),
    onPrimaryContainer = Color(0xFF4A0E24),
    secondary = Color(0xFF827717),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF558B2F),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF2D2E27),
    surface = Color(0xFFF0F0EA),
    onSurface = Color(0xFF2D2E27),
    surfaceVariant = Color(0xFFE4E4DB),
    onSurfaceVariant = Color(0xFF6E6D60),
    outline = Color(0xFFC9C8BD),
    outlineVariant = Color(0xFFDEDDD2),
)

// Dracula
private val DraculaDark = darkColorScheme(
    primary = Color(0xFFBD93F9),
    onPrimary = Color(0xFF191A21),
    primaryContainer = Color(0xFF3D2C56),
    onPrimaryContainer = Color(0xFFE4D8FF),
    secondary = Color(0xFFFF79C6),
    onSecondary = Color(0xFF2B1020),
    tertiary = Color(0xFF50FA7B),
    onTertiary = Color(0xFF0C2A14),
    background = Color(0xFF282A36),
    onBackground = Color(0xFFF8F8F2),
    surface = Color(0xFF21222C),
    onSurface = Color(0xFFF8F8F2),
    surfaceVariant = Color(0xFF343746),
    onSurfaceVariant = Color(0xFF8C94B0),
    outline = Color(0xFF44475A),
    outlineVariant = Color(0xFF363948),
)

private val DraculaLight = lightColorScheme(
    primary = Color(0xFF6F42C1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADBFF),
    onPrimaryContainer = Color(0xFF2E1065),
    secondary = Color(0xFFD81B60),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF17934B),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFCFCFE),
    onBackground = Color(0xFF2A2B34),
    surface = Color(0xFFF1F0F7),
    onSurface = Color(0xFF2A2B34),
    surfaceVariant = Color(0xFFE8E7F2),
    onSurfaceVariant = Color(0xFF606274),
    outline = Color(0xFFD4D3E1),
    outlineVariant = Color(0xFFE6E5F0),
)

// One Dark Pro / One Light
private val OneDarkDark = darkColorScheme(
    primary = Color(0xFF61AFEF),
    onPrimary = Color(0xFF1F232B),
    primaryContainer = Color(0xFF2F4160),
    onPrimaryContainer = Color(0xFFCFE4FB),
    secondary = Color(0xFFC678DD),
    onSecondary = Color(0xFF2A2133),
    tertiary = Color(0xFF98C379),
    onTertiary = Color(0xFF202B18),
    background = Color(0xFF282C34),
    onBackground = Color(0xFFABB2BF),
    surface = Color(0xFF21252B),
    onSurface = Color(0xFFABB2BF),
    surfaceVariant = Color(0xFF2C313A),
    onSurfaceVariant = Color(0xFF828B9A),
    outline = Color(0xFF3E4451),
    outlineVariant = Color(0xFF353B45),
)

private val OneDarkLight = lightColorScheme(
    primary = Color(0xFF4078F2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FB),
    onPrimaryContainer = Color(0xFF10305F),
    secondary = Color(0xFFA626A4),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF50A14F),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF383A42),
    surface = Color(0xFFF2F2F3),
    onSurface = Color(0xFF383A42),
    surfaceVariant = Color(0xFFE8E9EB),
    onSurfaceVariant = Color(0xFF73777F),
    outline = Color(0xFFD0D0D3),
    outlineVariant = Color(0xFFE4E4E7),
)

// Nord / Nord Light (Snow Storm)
private val NordDark = darkColorScheme(
    primary = Color(0xFF88C0D0),
    onPrimary = Color(0xFF22303C),
    primaryContainer = Color(0xFF3A527B),
    onPrimaryContainer = Color(0xFFD8EEF6),
    secondary = Color(0xFF81A1C1),
    onSecondary = Color(0xFF20303E),
    tertiary = Color(0xFFA3BE8C),
    onTertiary = Color(0xFF26301A),
    background = Color(0xFF2E3440),
    onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF3B4252),
    onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF434C5E),
    onSurfaceVariant = Color(0xFF9BA5B4),
    outline = Color(0xFF4C566A),
    outlineVariant = Color(0xFF454F61),
)

private val NordLight = lightColorScheme(
    primary = Color(0xFF5E81AC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E2F0),
    onPrimaryContainer = Color(0xFF22374E),
    secondary = Color(0xFF4F6D93),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF7F9159),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFECEFF4),
    onBackground = Color(0xFF2E3440),
    surface = Color(0xFFE5E9F0),
    onSurface = Color(0xFF2E3440),
    surfaceVariant = Color(0xFFDCE2ED),
    onSurfaceVariant = Color(0xFF4C566A),
    outline = Color(0xFFC3CBD9),
    outlineVariant = Color(0xFFD8DEE9),
)

// GitHub Dark / GitHub Light (Primer)
private val GitHubDark = darkColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF14406E),
    onPrimaryContainer = Color(0xFFCDE4FB),
    secondary = Color(0xFF8957E5),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF3FB950),
    onTertiary = Color(0xFF062D12),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF262C35),
)

private val GitHubLight = lightColorScheme(
    primary = Color(0xFF0969DA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF0A3069),
    secondary = Color(0xFF8250DF),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF1A7F37),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFF6F8FA),
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEFF2F6),
    onSurfaceVariant = Color(0xFF656D76),
    outline = Color(0xFFD0D7DE),
    outlineVariant = Color(0xFFD8DEE4),
)

// Solarized Dark / Solarized Light
private val SolarizedDark = darkColorScheme(
    primary = Color(0xFF268BD2),
    onPrimary = Color(0xFF00212B),
    primaryContainer = Color(0xFF0A3D5C),
    onPrimaryContainer = Color(0xFFBFE3F7),
    secondary = Color(0xFF2AA198),
    onSecondary = Color(0xFF002624),
    tertiary = Color(0xFF859900),
    onTertiary = Color(0xFF1D2200),
    background = Color(0xFF002B36),
    onBackground = Color(0xFF93A1A1),
    surface = Color(0xFF073642),
    onSurface = Color(0xFF93A1A1),
    surfaceVariant = Color(0xFF0E4654),
    onSurfaceVariant = Color(0xFF839496),
    outline = Color(0xFF1A4C5A),
    outlineVariant = Color(0xFF14404D),
)

private val SolarizedLight = lightColorScheme(
    primary = Color(0xFF1B6FA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE8F6),
    onPrimaryContainer = Color(0xFF0A3D5C),
    secondary = Color(0xFF198E88),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF647A00),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFDF6E3),
    onBackground = Color(0xFF073642),
    surface = Color(0xFFEEE8D5),
    onSurface = Color(0xFF073642),
    surfaceVariant = Color(0xFFE4DDC8),
    onSurfaceVariant = Color(0xFF657B83),
    outline = Color(0xFFCFC9B9),
    outlineVariant = Color(0xFFE0DAC9),
)

enum class AppThemeMode { SYSTEM, DARK, LIGHT }

/**
 * Named color styles, each modeled after a well-known VS Code theme. Every
 * style ships a dark and a light variant so [AppThemeMode] keeps working.
 */
enum class AppThemeStyle(val label: String, val dark: ColorScheme, val light: ColorScheme) {
    FORGE("Forge", ForgeDark, ForgeLight),
    DARK_PLUS("Dark+", DarkPlusDark, DarkPlusLight),
    MONOKAI("Monokai", MonokaiDark, MonokaiLight),
    DRACULA("Dracula", DraculaDark, DraculaLight),
    ONE_DARK("One Dark Pro", OneDarkDark, OneDarkLight),
    NORD("Nord", NordDark, NordLight),
    GITHUB("GitHub", GitHubDark, GitHubLight),
    SOLARIZED("Solarized", SolarizedDark, SolarizedLight),
}

@Composable
fun PocketTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    themeStyle: AppThemeStyle = AppThemeStyle.FORGE,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = if (isDark) themeStyle.dark else themeStyle.light,
        content = content,
    )
}
