package ee.schimke.composeai.clients.player

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import ee.schimke.composeai.clients.preview.DeviceProfile

/**
 * This device as a preview target: the space this box was given (the app's window when the box
 * fills it), at the display's density, the user's font scale, light/dark, locale and direction.
 * Changes — rotation, a font-scale change, dark mode — produce a new profile, and the preview
 * re-lays out for it.
 */
@Composable
fun BoxWithConstraintsScope.rememberDeviceProfile(): DeviceProfile? {
  val density = LocalDensity.current
  val configuration = LocalConfiguration.current
  val dark = isSystemInDarkTheme()
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val width = constraints.maxWidth
  val height = constraints.maxHeight
  val locale = configuration.locales.get(0)?.toLanguageTag() ?: "en-US"
  val round = configuration.isScreenRound
  return remember(width, height, density, dark, rtl, locale, round) {
    if (width <= 0 || height <= 0 || width == Int.MAX_VALUE || height == Int.MAX_VALUE) null
    else
      DeviceProfile(
        widthPx = width,
        heightPx = height,
        density = density.density,
        fontScale = density.fontScale,
        dark = dark,
        localeTag = locale,
        rtl = rtl,
        round = round,
      )
  }
}
