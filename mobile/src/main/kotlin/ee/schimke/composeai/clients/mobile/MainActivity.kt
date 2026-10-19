package ee.schimke.composeai.clients.mobile

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import ee.schimke.composeai.clients.KtorStreamTransport
import ee.schimke.composeai.clients.StreamTransport
import ee.schimke.composeai.clients.player.LocalFiles
import ee.schimke.composeai.clients.preview.PreviewLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** The default transport every screen uses: a Ktor WebSocket client over the OkHttp engine. */
fun defaultTransportFactory(): StreamTransport.Factory = KtorStreamTransport.factory

/**
 * Single activity. Tapping a link — a UI-builder design, a `.rc` URL, a `composeai://` session — or
 * opening a `.rc` / `.uid` file launches it here; [resolve] turns the intent into a [PreviewLink]
 * and the preview fills the window edge to edge, as an app would. mDNS discovery runs only while
 * resumed.
 */
class MainActivity : ComponentActivity() {

  private val link = MutableStateFlow<PreviewLink?>(null)
  private val problem = MutableStateFlow<String?>(null)
  private val discovered =
    MutableStateFlow<List<ee.schimke.composeai.clients.discovery.DiscoveredSession>>(emptyList())
  private val discovery by lazy { NsdSessionDiscovery(applicationContext) }
  private val settings by lazy { UiBuilderSettings(applicationContext) }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    open(resolve(intent))
    discovery.sessions.onEach { discovered.value = it }.launchIn(lifecycleScope)
    // A preview is being looked at: don't let the screen time out under it.
    link
      .onEach { current ->
        if (current != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
      .launchIn(lifecycleScope)

    setContent {
      val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
      MaterialTheme(colorScheme = colors) {
        val current by link.collectAsState()
        val sessions by discovered.collectAsState()
        val message by problem.collectAsState()
        SessionViewerApp(
          link = current,
          discoveredSessions = sessions,
          settings = settings,
          problem = message,
          onOpen = { open(it) },
          onOpenFile = { uri -> open(fileLink(uri)) },
          onDisconnect = { link.value = null },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    open(resolve(intent))
  }

  override fun onResume() {
    super.onResume()
    discovery.start()
  }

  override fun onPause() {
    discovery.stop()
    super.onPause()
  }

  private fun open(target: PreviewLink?) {
    if (target != null) {
      problem.value = null
      link.value = target
    }
  }

  /** A VIEW intent's data is the tapped link or opened file; null on a plain launcher start. */
  private fun resolve(intent: Intent?): PreviewLink? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val data = intent.dataString ?: return null
    val scheme = intent.data?.scheme
    return if (scheme == "content" || scheme == "file") fileLink(data) else PreviewLink.parse(data)
  }

  /** A local file: a `.uid` is compiled on the configured UI builder, anything else is played. */
  private fun fileLink(uri: String): PreviewLink? {
    if (!LocalFiles.isDesign(this, uri)) return PreviewLink.LocalDocument(uri)
    val server = settings.server
    if (server == null) {
      problem.value = "Set a UI builder server to open .uid files."
      return null
    }
    return PreviewLink.DesignFile(uri, server, settings.token)
  }
}
