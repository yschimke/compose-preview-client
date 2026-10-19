package ee.schimke.composeai.clients.wear

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import ee.schimke.composeai.clients.KtorStreamTransport
import ee.schimke.composeai.clients.StreamTransport
import ee.schimke.composeai.clients.discovery.DiscoveredSession
import ee.schimke.composeai.clients.preview.PreviewLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** The default transport: a Ktor WebSocket client over the OkHttp engine. */
fun defaultTransportFactory(): StreamTransport.Factory = KtorStreamTransport.factory

/**
 * Single watch activity. A tapped or forwarded link — a UI-builder design, a `.rc` URL, a
 * `composeai://` session — opens straight into a full-screen preview on the watch face.
 */
class MainActivity : ComponentActivity() {

  private val link = MutableStateFlow<PreviewLink?>(null)
  private val discovered = MutableStateFlow<List<DiscoveredSession>>(emptyList())
  private val discovery by lazy { NsdSessionDiscovery(applicationContext) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    link.value = resolveLink(intent)
    discovery.sessions.onEach { discovered.value = it }.launchIn(lifecycleScope)
    // Keep the watch awake (out of ambient) while a preview is up.
    link
      .onEach { current ->
        if (current != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
      .launchIn(lifecycleScope)

    setContent {
      val current by link.collectAsState()
      val sessions by discovered.collectAsState()
      SessionViewerApp(
        link = current,
        discoveredSessions = sessions,
        onConnect = { link.value = it },
        onDisconnect = { link.value = null },
      )
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    resolveLink(intent)?.let { link.value = it }
  }

  override fun onResume() {
    super.onResume()
    discovery.start()
  }

  override fun onPause() {
    discovery.stop()
    super.onPause()
  }

  private fun resolveLink(intent: Intent?): PreviewLink? =
    if (intent?.action == Intent.ACTION_VIEW) PreviewLink.parse(intent.dataString) else null
}
