package ee.schimke.composeai.clients.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.composeai.clients.SessionClient
import ee.schimke.composeai.clients.SessionLink
import ee.schimke.composeai.clients.SessionState
import ee.schimke.composeai.clients.SessionTarget
import ee.schimke.composeai.clients.discovery.DiscoveredSession
import ee.schimke.composeai.clients.player.DevicePreview
import ee.schimke.composeai.clients.player.LocalFiles
import ee.schimke.composeai.clients.player.PreviewOverlayState
import ee.schimke.composeai.clients.preview.DeviceProfile
import ee.schimke.composeai.clients.preview.PreviewLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Wear app root — straight into the full-screen [PreviewScreen] for a tapped link, else the
 * [ConnectScreen].
 */
@Composable
fun SessionViewerApp(
  link: PreviewLink?,
  discoveredSessions: List<DiscoveredSession>,
  onConnect: (PreviewLink) -> Unit,
  onDisconnect: () -> Unit,
) {
  MaterialTheme {
    if (link == null) {
      ConnectScreen(discoveredSessions, onConnect)
    } else {
      PreviewScreen(link, onDisconnect)
    }
  }
}

/**
 * A device preview on the watch face: Remote Compose designs and documents play on the watch itself
 * at its real (round) size; anything else streams from the host rendered for this screen. A swipe
 * back leaves.
 */
@Composable
fun PreviewScreen(link: PreviewLink, onDisconnect: () -> Unit) {
  BackHandler(onBack = onDisconnect)
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    DevicePreview(
      link = link,
      stream = { session, device -> SessionScreen(session, onDisconnect, device) },
      overlay = { PreviewChrome(it, onBack = onDisconnect) },
    )
  }
}

@Composable
fun SessionScreen(
  link: SessionLink,
  onDisconnect: () -> Unit,
  device: DeviceProfile? = null,
  clientFactory: () -> SessionClient = { SessionClient(defaultTransportFactory()) },
) {
  val client = remember(link) { clientFactory() }
  val state by client.state.collectAsState()
  val frame by client.frame.collectAsState()

  LaunchedEffect(link) { client.connect(link) }
  // Close the WebSocket when the screen leaves or the link changes, so the session doesn't leak.
  DisposableEffect(link) { onDispose { client.close("left session screen") } }
  // Render for this watch's screen on every (re)connect and whenever the device changes.
  LaunchedEffect(client, device) {
    if (device == null) return@LaunchedEffect
    client.state.filterIsInstance<SessionState.Connected>().collect {
      client.setOverrides(device.streamOverrides())
    }
  }

  Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
    FrameCanvas(frame = frame, modifier = Modifier.fillMaxSize(), onInput = { client.send(it) })
    if (frame == null || state is SessionState.Failed || state is SessionState.Closed) {
      StatusOverlay(state = state, target = link.target, onDismiss = onDisconnect)
    }
  }
}

/**
 * Watch chrome over a preview: a problem takes the screen (with Back); otherwise the label shows
 * briefly at the top of the round face and fades, leaving the design.
 */
@Composable
fun PreviewChrome(state: PreviewOverlayState, onBack: () -> Unit) {
  if (state.error != null || state.mode == null) {
    Box(
      Modifier.fillMaxSize().background(Color(0xCC000000)).padding(16.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          if (state.error != null) "Can't preview" else "Opening…",
          color = Color.White,
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
        )
        Text(
          state.error ?: state.label,
          color = Color.White.copy(alpha = 0.7f),
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
          maxLines = 4,
        )
        Button(onClick = onBack) { Text("Back") }
      }
    }
    return
  }
  var visible by remember(state.mode) { mutableStateOf(true) }
  LaunchedEffect(state.mode) {
    delay(2_000)
    visible = false
  }
  val message = state.notice ?: state.action ?: state.label.takeIf { visible || state.loading }
  if (message != null) {
    Box(Modifier.fillMaxSize().padding(top = 18.dp), contentAlignment = Alignment.TopCenter) {
      Text(
        message,
        color = if (state.notice != null) Color(0xFFFFB4AB) else Color.White,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier =
          Modifier.background(Color(0xCC1B1B1F), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
      )
    }
  }
}

@Composable
private fun StatusOverlay(state: SessionState, target: SessionTarget, onDismiss: () -> Unit) {
  Box(
    Modifier.fillMaxSize().background(Color(0xCC000000)).padding(16.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val (title, detail) =
        when (state) {
          is SessionState.Connecting,
          SessionState.Idle -> "Connecting…" to target.label
          is SessionState.Connected -> "Loading…" to target.label
          is SessionState.Failed -> "Failed" to state.message
          is SessionState.Closed -> "Closed" to state.reason
        }
      Text(
        title,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
      )
      Text(
        detail,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
      )
      Button(onClick = onDismiss) { Text("Back") }
    }
  }
}

@Composable
fun ConnectScreen(discoveredSessions: List<DiscoveredSession>, onConnect: (PreviewLink) -> Unit) {
  Column(
    Modifier.fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 12.dp, vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      "Session Viewer",
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
    )
    // Bundled documents: check on-watch playback with no server at all.
    LocalFiles.SAMPLES.forEach { sample ->
      val link = PreviewLink.LocalDocument(sample)
      Button(onClick = { onConnect(link) }, modifier = Modifier.fillMaxWidth()) {
        Text("Sample: ${link.label.removeSuffix(".rc")}")
      }
    }
    if (discoveredSessions.isEmpty()) {
      Text(
        "Open a design, a .rc link or a session link from your phone, or wait for a server on this network.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    } else {
      discoveredSessions.forEach { session ->
        Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
          Text(session.moduleLabel ?: session.name, style = MaterialTheme.typography.labelMedium)
          Text("${session.host}:${session.port}", style = MaterialTheme.typography.bodySmall)
        }
      }
    }
  }
}

// --- @Preview surfaces (visual evidence for the watch chrome) ---

private val SAMPLE = SessionLink("192.168.1.20", 7341, "tok", SessionTarget.Preview("HomeTile"))

@Preview(
  name = "Wear connecting",
  showBackground = true,
  backgroundColor = 0xFF000000,
  widthDp = 220,
  heightDp = 220,
)
@Composable
private fun WearConnectingPreview() {
  MaterialTheme { StatusOverlay(SessionState.Connecting(SAMPLE), SAMPLE.target, onDismiss = {}) }
}

@Preview(
  name = "Wear failed",
  showBackground = true,
  backgroundColor = 0xFF000000,
  widthDp = 220,
  heightDp = 220,
)
@Composable
private fun WearFailedPreview() {
  MaterialTheme {
    StatusOverlay(SessionState.Failed("No route to host"), SAMPLE.target, onDismiss = {})
  }
}

@Preview(
  name = "Wear preview chrome",
  showBackground = true,
  backgroundColor = 0xFF000000,
  widthDp = 220,
  heightDp = 220,
)
@Composable
private fun WearPreviewChromePreview() {
  MaterialTheme {
    Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
      PreviewChrome(
        PreviewOverlayState(label = "weather-tile", mode = "Playing on device"),
        onBack = {},
      )
    }
  }
}

@Preview(
  name = "Wear connect",
  showBackground = true,
  backgroundColor = 0xFF000000,
  widthDp = 220,
  heightDp = 220,
)
@Composable
private fun WearConnectPreview() {
  MaterialTheme { ConnectScreen(discoveredSessions = emptyList(), onConnect = {}) }
}
