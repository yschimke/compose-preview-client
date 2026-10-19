package ee.schimke.composeai.clients.preview

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The display a preview is shown on: this phone or watch, as it is right now. Everything a server
 * renders for the device is told these values, so a design lays out at the device's real window,
 * density, font scale, theme and locale instead of the size it was authored at.
 *
 * Pixel sizes are the preview surface (the app's content area), not the panel: the preview is what
 * an app would get as its window.
 */
data class DeviceProfile(
  val widthPx: Int,
  val heightPx: Int,
  /** Pixels per dp (`DisplayMetrics.density`). */
  val density: Float,
  /** `Configuration.fontScale`. */
  val fontScale: Float = 1f,
  val dark: Boolean = false,
  /** BCP-47, e.g. `en-US`. */
  val localeTag: String = "en-US",
  val rtl: Boolean = false,
  /** A round watch face. */
  val round: Boolean = false,
) {
  init {
    require(widthPx > 0 && heightPx > 0) { "size must be positive: ${widthPx}x$heightPx" }
    require(density > 0f) { "density must be positive: $density" }
  }

  val widthDp: Int
    get() = (widthPx / density).roundToInt()

  val heightDp: Int
    get() = (heightPx / density).roundToInt()

  /**
   * The streamed-frame lane's `setOverrides` block (`composeai-stream/1`). The keys are the serve
   * lane's `PreviewOverrides` names; a server that cannot honour one says so in an `error` message
   * and keeps streaming.
   */
  fun streamOverrides(): Map<String, String> = buildMap {
    put("widthPx", widthPx.toString())
    put("heightPx", heightPx.toString())
    put("density", density.toString())
    put("fontScale", fontScale.toString())
    put("uiMode", if (dark) "dark" else "light")
    put("localeTag", localeTag)
  }

  /**
   * [environment] (a design document's `environment` object) re-pointed at this device. Fields this
   * profile has no opinion on — fixed time, animations, posture, export devices — are kept, as are
   * any the schema grows later.
   */
  fun applyTo(environment: JsonObject?): JsonObject = buildJsonObject {
    environment?.forEach { (key, value) -> put(key, value) }
    put("widthDp", JsonPrimitive(widthDp))
    put("heightDp", JsonPrimitive(heightDp))
    put("density", JsonPrimitive(density))
    put("fontScale", JsonPrimitive(fontScale))
    put("theme", JsonPrimitive(if (dark) "dark" else "light"))
    put("locale", JsonPrimitive(localeTag))
    put("layoutDirection", JsonPrimitive(if (rtl) "rtl" else "ltr"))
  }
}
