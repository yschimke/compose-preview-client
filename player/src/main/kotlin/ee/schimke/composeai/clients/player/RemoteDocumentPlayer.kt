@file:Suppress("RestrictedApi")

package ee.schimke.composeai.clients.player

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
  val player =
    remember(bytes) {
      runCatching {
        RemoteComposePlayer(context).apply {
          setDocument(RemoteDocument(decode(bytes)))
          addIdActionListener { id, value -> currentOnAction.value(id, value) }
        }
      }
    }
  player.exceptionOrNull()?.let { error -> LaunchedEffect(error) { currentOnError.value(error) } }
  player.getOrNull()?.let { view ->
    key(view) { AndroidView(factory = { view }, modifier = modifier.fillMaxSize()) }
  }
}

/** Decode `.rc` bytes; throws on a document this player version can't read. */
fun decode(bytes: ByteArray): CoreDocument =
  CoreDocument().apply {
    initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(bytes)))
  }
