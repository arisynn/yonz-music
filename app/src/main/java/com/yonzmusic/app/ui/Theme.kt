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
import androidx.compose.ui.text.font.Font
import com.yonzmusic.app.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Design {
    val xs = 4.dp; val sm = 8.dp; val md = 10.dp; val gap = 12.dp; val page = 16.dp; val section = 20.dp
    val small = RoundedCornerShape(10.dp); val card = RoundedCornerShape(16.dp); val large = RoundedCornerShape(20.dp)
    val icon = 24.dp; val touch = 48.dp
    const val motion = 240
    const val glassOpacity = .90f
    val brandMist = Color(0xFFCFEAF5)
}
private val Dark = darkColorScheme(
    primary = Color(0xFF79E2CB), onPrimary = Color(0xFF00382F), primaryContainer = Color(0xFF183E39), onPrimaryContainer = Color(0xFFB8F7E7),
    secondary = Color(0xFFAABFBA), onSecondary = Color(0xFF182C27), secondaryContainer = Color(0xFF243731), onSecondaryContainer = Color(0xFFE0EEE9),
    background = Color(0xFF101214), onBackground = Color(0xFFF0F4F1), surface = Color(0xFF141D20), onSurface = Color(0xFFF0F4F1),
    surfaceVariant = Color(0xFF243034), onSurfaceVariant = Color(0xFFAABAB9), outline = Color(0xFF738682), outlineVariant = Color(0xFF303F40),
    error = Color(0xFFFFB4AB)
)
private val Light = lightColorScheme(
    primary = Color(0xFF006B59), onPrimary = Color.White, primaryContainer = Color(0xFFCBF1E5), onPrimaryContainer = Color(0xFF164C40),
    secondary = Color(0xFF48655D), onSecondary = Color.White, secondaryContainer = Color(0xFFDCEBE5), onSecondaryContainer = Color(0xFF244138),
    background = Color(0xFFF5F5F7), onBackground = Color(0xFF172B2B), surface = Color(0xFFFDFDF8), onSurface = Color(0xFF172B2B),
    surfaceVariant = Color(0xFFE4EAE3), onSurfaceVariant = Color(0xFF50635E), outline = Color(0xFF71837B), outlineVariant = Color(0xFFD4DDD5),
    error = Color(0xFF9F3129)
)
private val AppFont=FontFamily(Font(R.font.inter_regular,FontWeight.Normal),Font(R.font.inter_medium,FontWeight.Medium),Font(R.font.inter_semibold,FontWeight.SemiBold))
private val BaseType = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 41.sp, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-.8).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .2.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .4.sp)
)
private val Type=with(BaseType) {Typography(displayLarge=displayLarge.copy(fontFamily=AppFont),displayMedium=displayMedium.copy(fontFamily=AppFont),displaySmall=displaySmall.copy(fontFamily=AppFont),headlineLarge=headlineLarge.copy(fontFamily=AppFont),headlineMedium=headlineMedium.copy(fontFamily=AppFont),headlineSmall=headlineSmall.copy(fontFamily=AppFont),titleLarge=titleLarge.copy(fontFamily=AppFont),titleMedium=titleMedium.copy(fontFamily=AppFont),titleSmall=titleSmall.copy(fontFamily=AppFont),bodyLarge=bodyLarge.copy(fontFamily=AppFont),bodyMedium=bodyMedium.copy(fontFamily=AppFont),bodySmall=bodySmall.copy(fontFamily=AppFont),labelLarge=labelLarge.copy(fontFamily=AppFont),labelMedium=labelMedium.copy(fontFamily=AppFont),labelSmall=labelSmall.copy(fontFamily=AppFont))}
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
