package com.yonzmusic.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Design {
    val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val gap = 16.dp; val page = 24.dp; val section = 28.dp
    val small = RoundedCornerShape(12.dp); val card = RoundedCornerShape(24.dp); val large = RoundedCornerShape(32.dp)
    val icon = 24.dp; val touch = 48.dp
    const val motion = 240
    const val glassOpacity = .90f
    val brandMist = Color(0xFFCFEAF5)
}
private val Dark = darkColorScheme(
    primary = Color(0xFF79E2CB), onPrimary = Color(0xFF00382F), primaryContainer = Color(0xFF183E39), onPrimaryContainer = Color(0xFFB8F7E7),
    secondary = Color(0xFFC2B4ED), onSecondary = Color(0xFF30264C), secondaryContainer = Color(0xFF302D40), onSecondaryContainer = Color(0xFFE6DDFB),
    background = Color(0xFF0E1416), onBackground = Color(0xFFF0F4F1), surface = Color(0xFF141D20), onSurface = Color(0xFFF0F4F1),
    surfaceVariant = Color(0xFF243034), onSurfaceVariant = Color(0xFFAABAB9), outline = Color(0xFF738682), outlineVariant = Color(0xFF303F40),
    error = Color(0xFFFFB4AB)
)
private val Light = lightColorScheme(
    primary = Color(0xFF006B59), onPrimary = Color.White, primaryContainer = Color(0xFFCBF1E5), onPrimaryContainer = Color(0xFF164C40),
    secondary = Color(0xFF67548D), onSecondary = Color.White, secondaryContainer = Color(0xFFE9E0F7), onSecondaryContainer = Color(0xFF463061),
    background = Color(0xFFF5F5EF), onBackground = Color(0xFF172B2B), surface = Color(0xFFFDFDF8), onSurface = Color(0xFF172B2B),
    surfaceVariant = Color(0xFFE4EAE3), onSurfaceVariant = Color(0xFF50635E), outline = Color(0xFF71837B), outlineVariant = Color(0xFFD4DDD5),
    error = Color(0xFF9F3129)
)
private val Type = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 41.sp, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-.8).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .2.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.5.sp)
)
internal val LocalReducedMotion = staticCompositionLocalOf { false }
@Composable fun YonzTheme(theme: String, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (theme) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    CompositionLocalProvider(LocalReducedMotion provides (reduceMotion || !android.animation.ValueAnimator.areAnimatorsEnabled())) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = Shapes(small = Design.small, medium = Design.card, large = Design.large), content = content)
    }
}

/** Small entry transition; the native dialog supplies dismissal. No artwork/decoder work on animation frames. */
@Composable internal fun PlayerReveal(content:@Composable ()->Unit) {
    val reduced=LocalReducedMotion.current
    val view=androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect { if(reduced)(view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setWindowAnimations(0) }
    val visible=androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(reduced).apply { targetState=true } }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(visibleState=visible,enter=androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if(reduced)0 else 160)),exit=androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(0))) { content() }
    }
}


/** Match dialog system-bar controls to the app theme, including three-button navigation. */
@Composable internal fun DialogSystemBars() {
    val view=androidx.compose.ui.platform.LocalView.current
    val light=MaterialTheme.colorScheme.background.luminance()>.5f
    androidx.compose.runtime.SideEffect {
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let {window->
            androidx.core.view.WindowCompat.getInsetsController(window,view).apply {
                isAppearanceLightStatusBars=light;isAppearanceLightNavigationBars=light
            }
            window.statusBarColor=android.graphics.Color.TRANSPARENT
            window.navigationBarColor=android.graphics.Color.TRANSPARENT
            if(android.os.Build.VERSION.SDK_INT>=29) {window.isStatusBarContrastEnforced=false;window.isNavigationBarContrastEnforced=false}
        }
    }
}
