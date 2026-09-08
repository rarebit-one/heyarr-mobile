package one.rarebit.heyarr.mobile.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE design-token layer — ported verbatim from heyarr-desktop's `theme/Tokens.kt`.
 * Every colour, radius, spacing step and type size the UI uses is named here and
 * nowhere else. Media-keyed accents live in [MediaTheme]; these are the constant base
 * the accents sit on so a mixed-media screen reads as one app.
 */
object Tokens {
    // Surfaces — near-black, warm-neutral.
    val bgBase = Color(0xFF080709)
    val surface1 = Color(0xFF131116)
    val surface2 = Color(0xFF1B1922)
    val surface3 = Color(0xFF232029)
    val border = Color(0xFF2A2833)

    // Text ramp.
    val textPrimary = Color(0xFFF5F5F4)
    val textMuted = Color(0xFFA09F9D)
    val textDisabled = Color(0xFF6E6D72)

    // Semantic.
    val ratingGold = Color(0xFFF5C518)
    val danger = Color(0xFFE5484D)
    val warning = Color(0xFFF5A524)
    val success = Color(0xFF21C063)

    // Default accent = the Movie emerald; also the app's neutral CTA.
    val accent = Color(0xFF00935E)
    val accentHover = Color(0xFF12A96E)
    val accentGradEnd = Color(0xFF21C063)

    /** Neutral slate accent for unknown / non-media types (documents, feeds). */
    val slate = Color(0xFF7A8598)
    val slateHover = Color(0xFF8C97AA)
    val slateGradEnd = Color(0xFF9AA5B8)

    // Radii.
    val radiusCard: Dp = 14.dp
    val radiusButton: Dp = 10.dp
    val radiusInput: Dp = 10.dp
    val radiusPill: Dp = 999.dp
    val radiusChip: Dp = 8.dp

    // Spacing (4px base).
    val s1: Dp = 4.dp
    val s2: Dp = 8.dp
    val s3: Dp = 12.dp
    val s4: Dp = 16.dp
    val s6: Dp = 24.dp
    val s8: Dp = 32.dp
    val s12: Dp = 48.dp
    val gridGap: Dp = 20.dp

    // Elevation hairline.
    val hairline: Dp = 1.dp

    // Layout. The phone shows a bottom bar; from [railBreakpoint] up (a tablet, a
    // foldable open, a phone in landscape) the destinations move to a left rail.
    val navWidth: Dp = 92.dp
    val railBreakpoint: Dp = 600.dp
    val posterWidth: Dp = 132.dp
    val posterWidthWide: Dp = 160.dp
    val squareWidth: Dp = 148.dp
    val screenPadding: Dp = 16.dp

    // Type scale (Montserrat display / Inter body).
    object Type {
        val h1 = 40.sp
        val h2 = 32.sp
        val h3 = 24.sp
        val h4 = 20.sp
        val h5 = 16.sp
        val h6 = 14.sp
        val bodyXl = 18.sp
        val bodyL = 16.sp
        val bodyM = 14.sp
        val bodyS = 12.sp
        val bodyXs = 11.sp
    }
}
