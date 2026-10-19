@file:Suppress("RestrictedApi")

package ee.schimke.composeai.clients.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Plays a Remote Compose document full-size with the AndroidX player — the same `View` a host app
 * embeds, so what the device draws is what would ship. Touches, scrolls, animations, state and
 * clicks all run in the player on this device; nothing round-trips to a server.
 *
 * A new [bytes] builds a new player: the AndroidX player caches its layout at the first draw, so
 * swapping a document into a used one can paint stale. Decoding is cheap next to that.
 *
 * [onAction] receives the document's host actions (`id`, payload) — what a real host would route to
 * its own code; a preview surfaces them so they can be checked.
 */
@Composable
fun RemoteDocumentPlayer(
  bytes: ByteArray,
  modifier: Modifier = Modifier,
  onAction: (id: Int, value: Any?) -> Unit = { _, _ -> },
  onError: (Throwable) -> Unit = {},
) {
  val context = LocalContext.current
  val currentOnAction = rememberUpdatedState(onAction)
  val currentOnError = rememberUpdatedState(onError)
  BoxWithConstraints(modifier.fillMaxSize()) {
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    // Keyed on the size as well: a player measured and drawn once paints blank (or only up to its
    // first size) when re-measured at another, so a rotation gets a freshly primed one.
    val player =
      remember(bytes, width, height) {
        runCatching {
          RemoteComposePlayer(context).apply {
            setDocument(RemoteDocument(decode(bytes)))
            addIdActionListener { id, value -> currentOnAction.value(id, value) }
            prime(this, width, height)
          }
        }
      }
    player.exceptionOrNull()?.let { error -> LaunchedEffect(error) { currentOnError.value(error) } }
    player.getOrNull()?.let { view ->
      key(view) { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
    }
  }
}

/**
 * One off-screen measure → layout → draw at the slot's size before the player is shown, then a
 * forced re-measure. The AndroidX player only runs the document's measure pass once its paint
 * context exists, and that is created by the first draw — so without this a document whose layout
 * depends on measuring (state layouts, wrap-content cards) shows its first frame blank and can stay
 * that way. The same warm-up homeassistant-remotecompose's player does.
 */
private fun prime(view: RemoteComposePlayer, widthPx: Int, heightPx: Int) {
  if (widthPx <= 0 || heightPx <= 0 || widthPx == Constraints.Infinity || heightPx == Constraints.Infinity)
    return
  val w = widthPx.coerceAtMost(MAX_PRIME_PX)
  val h = heightPx.coerceAtMost(MAX_PRIME_PX)
  val widthSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
  val heightSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
  view.measure(widthSpec, heightSpec)
  view.layout(0, 0, w, h)
  val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  view.draw(Canvas(bitmap))
  bitmap.recycle()
  // Now the paint context exists: measure the document for real.
  view.document.document.invalidateMeasure()
  forceLayoutTree(view)
  view.measure(widthSpec, heightSpec)
  view.layout(0, 0, w, h)
}

private fun forceLayoutTree(view: View) {
  view.forceLayout()
  if (view is ViewGroup) for (i in 0 until view.childCount) forceLayoutTree(view.getChildAt(i))
}

private const val MAX_PRIME_PX = 4096

/** Decode `.rc` bytes; throws on a document this player version can't read. */
fun decode(bytes: ByteArray): CoreDocument =
  CoreDocument().apply {
    initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(bytes)))
  }
