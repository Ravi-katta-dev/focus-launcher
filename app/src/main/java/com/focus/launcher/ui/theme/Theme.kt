package com.focus.launcher.ui.theme

import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.focus.launcher.data.FontChoice
import com.focus.launcher.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The whole palette: one background, one foreground, and three greys between them. */
@Immutable
class FocusColors(
    val bg: Color,
    val fg: Color,
    /** Secondary text. */
    val dim: Color,
    /** Tertiary text, inactive marks, outlines. */
    val faint: Color,
    /** Hairlines and empty chart tracks. */
    val line: Color,
    val isDark: Boolean,
)

val BlackTheme = FocusColors(
    bg = Color.Black,
    fg = Color.White,
    dim = Color(0xFF8E8E8E),
    faint = Color(0xFF565656),
    line = Color(0xFF2A2A2A),
    isDark = true,
)

val WhiteTheme = FocusColors(
    bg = Color.White,
    fg = Color.Black,
    dim = Color(0xFF6A6A6A),
    faint = Color(0xFFA6A6A6),
    line = Color(0xFFDCDCDC),
    isDark = false,
)

val LocalFocusColors = staticCompositionLocalOf { BlackTheme }
val LocalFocusFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }
val LocalTextScale = staticCompositionLocalOf { 1f }

@Composable
fun FocusTheme(settings: Settings, content: @Composable () -> Unit) {
    val colors = if (settings.dark) BlackTheme else WhiteTheme
    val wallpaper = rememberWallpaper(settings.wallpaperUri)
    val font = when (settings.font) {
        FontChoice.SANS -> FontFamily.Default
        FontChoice.SERIF -> FontFamily.Serif
        FontChoice.MONO -> FontFamily.Monospace
    }
    CompositionLocalProvider(
        LocalFocusColors provides colors,
        LocalFocusFont provides font,
        LocalTextScale provides settings.textScale,
        LocalIndication provides PressIndication,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (wallpaper != null) {
                Image(
                    bitmap = wallpaper,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(Modifier.fillMaxSize().background(colors.bg.copy(alpha = 0.74f)))
            } else {
                Box(Modifier.fillMaxSize().background(colors.bg))
            }
            content()
        }
    }
}

@Composable
private fun rememberWallpaper(uriText: String) = run {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { config.screenWidthDp.dp.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { config.screenHeightDp.dp.roundToPx() }.coerceAtLeast(1)
    val uri = uriText.takeIf { it.isNotBlank() }?.let(Uri::parse)
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, uri, widthPx, heightPx) {
        value = if (uri == null) null else withContext(Dispatchers.IO) { decodeWallpaper(context, uri, widthPx, heightPx) }
    }
    bitmap
}

private fun decodeWallpaper(context: android.content.Context, uri: Uri, targetW: Int, targetH: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    return decoded.asImageBitmap()
}

private fun computeSampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
    var sample = 1
    if (srcW <= 0 || srcH <= 0) return sample
    while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) sample *= 2
    return sample.coerceAtLeast(1)
}

/** Transparent bars over a black (or white) window, and the optional hidden status bar. */
fun ComponentActivity.applyFocusWindow(dark: Boolean, hideStatusBar: Boolean = false) {
    val transparent = android.graphics.Color.TRANSPARENT
    val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    window.setBackgroundDrawable((if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE).toDrawable())

    preferHighestRefreshRate()

    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (hideStatusBar) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    } else {
        controller.show(WindowInsetsCompat.Type.statusBars())
    }
}

/**
 * Asks for the panel's fastest mode at the current resolution (120 Hz on most recent phones).
 * Some vendors hold unknown apps at 60 Hz, which makes every swipe and scroll look choppy.
 */
private fun ComponentActivity.preferHighestRefreshRate() {
    try {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return
        val current = display.mode
        val fastest = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (window.attributes.preferredDisplayModeId != fastest.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
        }
    } catch (_: Exception) {
    }
}

/**
 * Touch feedback without colour or ripples: whatever is touched lights up softly and fades back.
 * Installed as the default indication, so every clickable in the app gets it: app names, the
 * clock ring, settings rows, buttons.
 */
object PressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = -1
}

private class PressNode(private val interactionSource: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val glow = Animatable(0f)
    private var lightingUp: Job? = null
    private var pressCount = 0

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressCount++
                        lightingUp = launch { glow.animateTo(1f, tween(durationMillis = 90)) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        pressCount = (pressCount - 1).coerceAtLeast(0)
                        if (pressCount == 0) {
                            val rising = lightingUp
                            launch {
                                // Let the light come fully up first, so even the quickest tap is
                                // seen, then let it die away slowly.
                                rising?.join()
                                glow.animateTo(0f, tween(durationMillis = 380, easing = LinearOutSlowInEasing))
                            }
                        }
                    }
                }
            }
        }
    }

    // glow is read here, in the draw phase, so animating it redraws without recomposing anything.
    override fun ContentDrawScope.draw() {
        val level = glow.value
        if (level > 0.004f) {
            // Exactly the element's own bounds, so it can never spill over a dialog border or a
            // neighbouring button; elements that are bare text carry their own padding to give
            // the light some room. BlendMode.Difference makes the same white lighten a black
            // surface and darken a white one (the white theme, the inverted "primary" buttons)
            // without this node having to know the theme.
            val corner = 12.dp.toPx().coerceAtMost(size.minDimension / 2)
            drawRoundRect(
                color = Color.White.copy(alpha = MAX_GLOW * level),
                cornerRadius = CornerRadius(corner, corner),
                blendMode = BlendMode.Difference,
            )
        }
        drawContent()
    }

    private companion object {
        const val MAX_GLOW = 0.17f
    }
}
