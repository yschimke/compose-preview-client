package ee.schimke.composeai.clients.mobile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.clients.SessionClient
import ee.schimke.composeai.clients.SessionLink
import ee.schimke.composeai.clients.SessionState
import ee.schimke.composeai.clients.SessionTarget
import ee.schimke.composeai.clients.discovery.DiscoveredSession
import ee.schimke.composeai.clients.auth.AccessGrant
import ee.schimke.composeai.clients.auth.GrantStore
import ee.schimke.composeai.clients.auth.SignIn
import ee.schimke.composeai.clients.preview.ServerAddress
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import ee.schimke.composeai.clients.player.DevicePreview
import ee.schimke.composeai.clients.player.LocalFiles
import ee.schimke.composeai.clients.player.PreviewOverlayState
import ee.schimke.composeai.clients.player.RemoteDocumentPlayer
import ee.schimke.composeai.clients.preview.DeviceProfile
import ee.schimke.composeai.clients.preview.PreviewLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance

/**
 * The mobile app root. With a [link] it goes straight into the full-screen [PreviewScreen]; with no
 * link it shows the [ConnectScreen] (paste a link, open a file, pick a discovered server). Material
 * 3 throughout.
 */
@Composable
fun SessionViewerApp(
  link: PreviewLink?,
  discoveredSessions: List<DiscoveredSession>,
  settings: UiBuilderSettings,
  grants: GrantStore,
  problem: String?,
  onOpen: (PreviewLink) -> Unit,
  onOpenFile: (String) -> Unit,
  onSignedIn: (AccessGrant) -> Unit,
  onDisconnect: () -> Unit,
) {
  var signingInTo by remember { mutableStateOf<ServerAddress?>(null) }
  // Grants live in preferences, which Compose can't observe; bump to re-read after a change.
  var accountVersion by remember { mutableIntStateOf(0) }
  val scope = rememberCoroutineScope()
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    val server = signingInTo
    if (link == null && server != null) {
      BackHandler { signingInTo = null }
      Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), Alignment.Center) {
        SignInPanel(
          server,
          grants,
          onSignedIn = {
            signingInTo = null
            accountVersion++
            onSignedIn(it)
          },
          onCancel = { signingInTo = null },
        )
      }
    } else if (link == null) {
      var serverUrl by remember { mutableStateOf(settings.serverUrl) }
      var token by remember { mutableStateOf(settings.token.orEmpty()) }
      ConnectScreen(
        discoveredSessions = discoveredSessions,
        serverUrl = serverUrl,
        onServerUrlChange = {
          serverUrl = it
          settings.serverUrl = it
        },
        token = token,
        onTokenChange = {
          token = it
          settings.token = it
        },
        account =
          remember(serverUrl, accountVersion) {
            UiBuilderSettings.parseServer(serverUrl)?.let { AccountState(it, grants.valid(it)) }
          },
        onSignIn = { signingInTo = it },
        onSignOut = { server ->
          scope.launch {
            SignIn(server, grants).signOut()
            accountVersion++
          }
        },
        problem = problem,
        onOpen = onOpen,
        onOpenFile = onOpenFile,
      )
    } else {
      PreviewScreen(
        link = link,
        grants = grants,
        onSignedIn = onSignedIn,
        onDisconnect = onDisconnect,
      )
    }
  }
}

/**
 * The device preview: the design or document fills the window edge to edge, like the app it is a
 * preview of, with only a brief label and any problems drawn over it. Back leaves.
 */
@Composable
fun PreviewScreen(
  link: PreviewLink,
  grants: GrantStore,
  onSignedIn: (AccessGrant) -> Unit,
  onDisconnect: () -> Unit,
) {
  BackHandler(onBack = onDisconnect)
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    DevicePreview(
      link = link,
      stream = { session, device ->
        SessionScreen(link = session, device = device, onDisconnect = onDisconnect)
      },
      overlay = { state ->
        val needed = state.signIn
        if (needed != null) SignInOverlay(needed, grants, onSignedIn, onBack = onDisconnect)
        else PreviewChrome(state, onBack = onDisconnect)
      },
    )
  }
}

/**
 * Drives one [SessionClient] for [link]: connects on entry, tells the server what [device] to
 * render for, paints frames, forwards input, and tears the session down when the user leaves.
 * Overlays connection chrome until the first frame lands.
 */
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
  // Close the WebSocket when the screen leaves or the link changes (a new link `remember`s a fresh
  // client), so we don't leak the old session's coroutine + server-side hold.
  DisposableEffect(link) { onDispose { client.close("left session screen") } }
  // Render for this screen: on every (re)connect, and whenever the device changes (rotation, font
  // scale, dark mode) — the open lane re-renders without reconnecting.
  LaunchedEffect(client, device) {
    if (device == null) return@LaunchedEffect
    client.state.filterIsInstance<SessionState.Connected>().collect {
      client.setOverrides(device.streamOverrides())
    }
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    FrameCanvas(frame = frame, modifier = Modifier.fillMaxSize(), onInput = { client.send(it) })

    // Connection chrome — shown until a frame is painted, or on failure.
    if (frame == null || state is SessionState.Failed || state is SessionState.Closed) {
      StatusOverlay(state = state, target = link.target, onDismiss = onDisconnect)
    }
  }
}

/**
 * Chrome over a device preview, kept out of the way: the label and how it's being shown appear for
 * a moment and fade, so the screen is the design's. Problems stay until they're resolved.
 */
@Composable
fun PreviewChrome(state: PreviewOverlayState, onBack: () -> Unit) {
  if (state.error != null) {
    MessageOverlay(title = "Can't preview ${state.label}", detail = state.error, onBack = onBack)
    return
  }
  if (state.mode == null) {
    MessageOverlay(title = "Opening ${state.label}…", detail = null, onBack = onBack, busy = true)
    return
  }
  var labelVisible by remember(state.mode) { mutableStateOf(true) }
  LaunchedEffect(state.mode) {
    delay(3_000)
    labelVisible = false
  }
  Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
    AnimatedVisibility(
      visible = labelVisible || state.loading,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.align(Alignment.TopCenter),
    ) {
      Pill {
        if (state.following) {
          Box(Modifier.size(8.dp).background(Color(0xFF6DD58C), CircleShape))
        }
        Text(
          "${state.label} · ${state.mode}",
          color = Color.White,
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (state.loading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
      }
    }
    Column(
      Modifier.align(Alignment.BottomCenter),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      state.action?.let { Pill { Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium) } }
      state.notice?.let {
        Pill {
          Text(
            it,
            color = Color(0xFFFFDAD6),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun Pill(content: @Composable () -> Unit) {
  Row(
    Modifier.background(Color(0xCC1B1B1F), RoundedCornerShape(50))
      .padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    content()
  }
}

@Composable
private fun MessageOverlay(title: String, detail: String?, onBack: () -> Unit, busy: Boolean = false) {
  Box(
    Modifier.fillMaxSize().background(Color(0xCC101014)).safeDrawingPadding().padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (busy) CircularProgressIndicator()
      Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
      detail?.let {
        Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodyMedium)
      }
      TextButton(onClick = onBack) { Text("Back") }
    }
  }
}

@Composable
private fun StatusOverlay(state: SessionState, target: SessionTarget, onDismiss: () -> Unit) {
  Box(
    Modifier.fillMaxSize().background(Color(0xCC101014)).padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      when (state) {
        is SessionState.Connecting,
        SessionState.Idle -> {
          CircularProgressIndicator()
          Text(
            "Connecting to ${target.label}…",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
          )
        }
        is SessionState.Connected -> {
          CircularProgressIndicator()
          Text(
            "Waiting for first frame…",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
          )
        }
        is SessionState.Failed -> {
          Text("Couldn't connect", color = Color.White, style = MaterialTheme.typography.titleLarge)
          Text(
            state.message,
            color = Color(0xFFFFB4AB),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        is SessionState.Closed -> {
          Text("Session closed", color = Color.White, style = MaterialTheme.typography.titleLarge)
          Text(
            state.reason,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
      TextButton(onClick = onDismiss) { Text("Back") }
    }
  }
}

/**
 * The no-link landing screen: paste any link (a UI-builder design, a `.rc` URL, a session), open a
 * `.rc` / `.uid` file, or tap a server discovered on the LAN via mDNS. Discovery never carries the
 * token, so a discovered server still needs a link/token to open — the field is pre-filled with a
 * `composeai://` skeleton for that server when one is tapped.
 */
@Composable
fun ConnectScreen(
  discoveredSessions: List<DiscoveredSession>,
  serverUrl: String,
  onServerUrlChange: (String) -> Unit,
  token: String,
  onTokenChange: (String) -> Unit,
  account: AccountState?,
  onSignIn: (ServerAddress) -> Unit,
  onSignOut: (ServerAddress) -> Unit,
  problem: String?,
  onOpen: (PreviewLink) -> Unit,
  onOpenFile: (String) -> Unit,
) {
  var text by remember { mutableStateOf("") }
  val parsed = remember(text) { PreviewLink.parse(text) }
  val currentOnOpenFile by rememberUpdatedState(onOpenFile)
  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { currentOnOpenFile(it.toString()) }
    }

  Column(
    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Compose Device Preview", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Open a UI-builder design or a Remote Compose document on this device. Tap a link, paste " +
        "one below, or open a file.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    problem?.let {
      Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      label = { Text("Link") },
      placeholder = { Text("https://…/ui-builder/<design> · …/doc.rc") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    Button(
      onClick = { parsed?.let(onOpen) },
      enabled = parsed != null,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(if (parsed != null) "Open ${parsed.kind} ${parsed.label}" else "Open")
    }
    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
      Text("Open a .rc or .uid file")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(
        "Samples",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      LocalFiles.SAMPLES.forEach { sample ->
        val link = PreviewLink.LocalDocument(sample)
        TextButton(onClick = { onOpen(link) }) { Text(link.label.removeSuffix(".rc")) }
      }
    }

    Text("UI builder for .uid files", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
      value = serverUrl,
      onValueChange = onServerUrlChange,
      label = { Text("Server") },
      isError = UiBuilderSettings.parseServer(serverUrl) == null,
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    account?.let { AccountRow(it, onSignIn, onSignOut) }
    OutlinedTextField(
      value = token,
      onValueChange = onTokenChange,
      label = { Text("Operator token (optional)") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )

    if (discoveredSessions.isNotEmpty()) {
      Text("On this network", style = MaterialTheme.typography.titleMedium)
      discoveredSessions.forEach { session ->
        DiscoveredServerCard(
          session = session,
          onTap = {
            // Seed the field with everything but the token, which the user fills from their link.
            text = "composeai://session?host=${session.host}&port=${session.port}&preview=&token="
          },
        )
      }
    }
  }
}

/** Whether this device is signed in to [server], for the connect screen. */
data class AccountState(val server: ServerAddress, val grant: AccessGrant?)

@Composable
private fun AccountRow(
  account: AccountState,
  onSignIn: (ServerAddress) -> Unit,
  onSignOut: (ServerAddress) -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    val grant = account.grant
    Text(
      if (grant == null) "Not signed in to ${account.server.label}"
      else
        buildString {
          append("Signed in")
          grant.approvedBy?.let { append(" · approved by ").append(it) }
          grant.expiresAtMillis?.let {
            append(" · until ")
            append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
          }
        },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    if (grant == null) TextButton(onClick = { onSignIn(account.server) }) { Text("Sign in") }
    else TextButton(onClick = { onSignOut(account.server) }) { Text("Sign out") }
  }
}

private val PreviewLink.kind: String
  get() =
    when (this) {
      is PreviewLink.Design,
      is PreviewLink.DesignFile -> "design"
      is PreviewLink.RemoteDocument,
      is PreviewLink.LocalDocument -> "document"
      is PreviewLink.Stream -> "preview"
    }

@Composable
private fun DiscoveredServerCard(session: DiscoveredSession, onTap: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Text(
        session.name,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "${session.moduleLabel ?: "preview server"} · ${session.host}:${session.port}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = onTap) { Text("Use this server") }
    }
  }
}

// ---------------------------------------------------------------------------
// @Preview surfaces — visual evidence for the connect screen + preview chrome (a live preview
// needs a server or a document, so it's covered by the verify/e2e path, not a static render).
// ---------------------------------------------------------------------------

@Composable
private fun ConnectScreenSample(sessions: List<DiscoveredSession>, problem: String? = null) {
  ConnectScreen(
    discoveredSessions = sessions,
    serverUrl = UiBuilderSettings.DEFAULT_SERVER,
    onServerUrlChange = {},
    token = "",
    onTokenChange = {},
    account = AccountState(ServerAddress("preview.coo.ee", 443, true), grant = null),
    onSignIn = {},
    onSignOut = {},
    problem = problem,
    onOpen = {},
    onOpenFile = {},
  )
}

@Preview(name = "Connect — empty", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ConnectScreenEmptyPreview() {
  MaterialTheme { Surface { ConnectScreenSample(emptyList()) } }
}

@Preview(name = "Connect — discovered", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ConnectScreenDiscoveredPreview() {
  val sessions =
    listOf(
      DiscoveredSession(
        "compose-preview :samples:android",
        "192.168.1.20",
        7341,
        ":samples:android",
      ),
      DiscoveredSession("compose-preview :samples:wear", "192.168.1.21", 7342, ":samples:wear"),
    )
  MaterialTheme { Surface { ConnectScreenSample(sessions) } }
}

@Preview(name = "Status — connecting", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ConnectingOverlayPreview() {
  MaterialTheme {
    StatusOverlay(
      state = SessionState.Connecting(SAMPLE_LINK),
      target = SAMPLE_LINK.target,
      onDismiss = {},
    )
  }
}

@Preview(name = "Status — failed", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun FailedOverlayPreview() {
  MaterialTheme {
    StatusOverlay(
      state = SessionState.Failed("Connection refused (is `serve` running?)"),
      target = SAMPLE_LINK.target,
      onDismiss = {},
    )
  }
}

@Preview(name = "Preview — playing a design", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewChromePlayingPreview() {
  MaterialTheme {
    Box(Modifier.fillMaxSize().background(Color(0xFF3B4A6B))) {
      PreviewChrome(
        PreviewOverlayState(
          label = "cheeky-raccoon",
          mode = "Playing on device · compiled for this screen · r41",
          following = true,
          action = "action 3 · toggle",
        ),
        onBack = {},
      )
    }
  }
}

@Preview(name = "Preview — can't compile", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewChromeErrorPreview() {
  MaterialTheme {
    PreviewChrome(
      PreviewOverlayState(
        label = "home-screen",
        error = "The server refused access (HTTP 401). Open the design's link with a token, " +
          "or ask its owner to share it.",
      ),
      onBack = {},
    )
  }
}

/** The real AndroidX player drawing a bundled document — what a device preview of a `.rc` shows. */
@Preview(name = "Preview — sample document", widthDp = 360, heightDp = 720)
@Composable
private fun SampleDocumentPreview() {
  val context = LocalContext.current
  val sample = LocalFiles.SAMPLES.first()
  val bytes =
    remember { context.assets.open(sample.removePrefix(LocalFiles.ASSET_PREFIX)).use { it.readBytes() } }
  MaterialTheme(colorScheme = darkColorScheme()) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      RemoteDocumentPlayer(bytes)
      PreviewChrome(
        PreviewOverlayState(label = "weather.rc", mode = "Playing on device"),
        onBack = {},
      )
    }
  }
}

private val SAMPLE_LINK =
  SessionLink("192.168.1.20", 7341, "tok", SessionTarget.Preview("com.example.HomeScreen"))
