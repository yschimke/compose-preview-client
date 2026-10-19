package ee.schimke.composeai.clients.player

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.clients.SessionLink
import ee.schimke.composeai.clients.preview.DesignPreview
import ee.schimke.composeai.clients.preview.DesignPreviewState
import ee.schimke.composeai.clients.preview.DeviceProfile
import ee.schimke.composeai.clients.preview.PreviewContent
import ee.schimke.composeai.clients.preview.PreviewLink
import ee.schimke.composeai.clients.preview.RemoteDocumentSource
import ee.schimke.composeai.clients.preview.ServerAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What the app's chrome should say over a preview. */
data class PreviewOverlayState(
  val label: String,
  /** How the preview is being shown, e.g. "Playing on device · fitted"; null while loading. */
  val mode: String? = null,
  val loading: Boolean = false,
  /** Nothing can be shown, and why. */
  val error: String? = null,
  /** Something went wrong after a preview was showing; the last good one stays up. */
  val notice: String? = null,
  /** Edits made elsewhere reach this device live. */
  val following: Boolean = false,
  /** The last host action the document fired (`id` + payload), for a moment. */
  val action: String? = null,
  /**
   * The server wants this device signed in before it shows anything (it answered 401/403). The
   * app's chrome offers [ee.schimke.composeai.clients.auth.SignIn] for it, then reopens the link.
   */
  val signIn: SignInNeeded? = null,
)

/**
 * [server] refused the preview. [hadCredential] separates "sign in" from "your sign-in expired or
 * doesn't reach this" — the chrome words them differently, and both are fixed by signing in again.
 */
data class SignInNeeded(val server: ServerAddress, val status: Int, val hadCredential: Boolean)

/**
 * A full-screen device preview of [link]. Remote Compose documents — `.rc` files, and designs that
 * compile to them — are played here by the AndroidX player at this device's real size; [stream]
 * draws a frame-streamed render (an app's `SessionScreen`), told the [DeviceProfile] it should
 * render for; [overlay] is the app's chrome, drawn on top.
 */
@Composable
fun DevicePreview(
  link: PreviewLink,
  modifier: Modifier = Modifier,
  stream: @Composable (link: SessionLink, device: DeviceProfile) -> Unit,
  overlay: @Composable (PreviewOverlayState) -> Unit,
) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    val device = rememberDeviceProfile()
    val devices = remember { MutableStateFlow<DeviceProfile?>(null) }
    SideEffect { devices.value = device }
    var action by remember(link) { mutableStateOf<String?>(null) }
    LaunchedEffect(action) {
      if (action != null) {
        delay(2_500)
        action = null
      }
    }
    val onAction: (Int, Any?) -> Unit = { id, value ->
      action = if (value == null) "action $id" else "action $id · $value"
    }

    when (link) {
      is PreviewLink.Stream -> device?.let { stream(link.session, it) }
      is PreviewLink.Design -> {
        val preview = remember(link) { DesignPreview(link) }
        DesignContent(preview, devices, link.label, device, stream, overlay, action, onAction)
      }
      is PreviewLink.DesignFile -> {
        val context = LocalContext.current
        val document by
          produceState<Result<JsonObject>?>(null, link) {
            value =
              try {
                Result.success(
                  Json.parseToJsonElement(LocalFiles.read(context, link.uri).decodeToString())
                    .jsonObject
                )
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                Result.failure(e)
              }
          }
        val loaded = document
        when {
          loaded == null -> overlay(PreviewOverlayState(link.label, loading = true))
          loaded.isFailure ->
            overlay(
              PreviewOverlayState(
                link.label,
                error = "Not a .uid design: ${loaded.exceptionOrNull()?.message}",
              )
            )
          else -> {
            val preview = remember(link, loaded) { DesignPreview(link, loaded.getOrThrow()) }
            DesignContent(preview, devices, link.label, device, stream, overlay, action, onAction)
          }
        }
      }
      is PreviewLink.RemoteDocument -> {
        val state by
          produceState(DocumentState(), link) {
            // Polls until cancelled — or until the server refuses this device, which more polling
            // can't change: then it stops and asks for a sign-in.
            RemoteDocumentSource(link.url, link.token).watch().first { result ->
              val refused = result is RemoteDocumentSource.Result.Failed && result.status in AUTH_STATUSES
              value =
                when (result) {
                  is RemoteDocumentSource.Result.Changed -> DocumentState(result.bytes)
                  is RemoteDocumentSource.Result.Failed ->
                    value.copy(
                      failure = result.message,
                      loaded = true,
                      signIn =
                        link.authServer
                          ?.takeIf { refused }
                          ?.let { SignInNeeded(it, result.status ?: 401, link.token != null) },
                    )
                  RemoteDocumentSource.Result.Unchanged -> value
                }
              refused
            }
          }
        DocumentContent(state, link.label, "Playing on device · live", overlay, action, onAction)
      }
      is PreviewLink.LocalDocument -> {
        val context = LocalContext.current
        val state by
          produceState(DocumentState(), link) {
            value =
              try {
                DocumentState(LocalFiles.read(context, link.uri))
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                DocumentState(failure = e.message ?: "can't read the file", loaded = true)
              }
          }
        DocumentContent(state, link.label, "Playing on device", overlay, action, onAction)
      }
    }
  }
}

private data class DocumentState(
  val bytes: ByteArray? = null,
  val failure: String? = null,
  val loaded: Boolean = bytes != null,
  val signIn: SignInNeeded? = null,
)

private val AUTH_STATUSES = setOf(401, 403)

@Composable
private fun DocumentContent(
  state: DocumentState,
  label: String,
  mode: String,
  overlay: @Composable (PreviewOverlayState) -> Unit,
  action: String?,
  onAction: (Int, Any?) -> Unit,
) {
  var playerError by remember(state.bytes) { mutableStateOf<String?>(null) }
  state.bytes?.let { bytes ->
    RemoteDocumentPlayer(
      bytes,
      onAction = onAction,
      onError = { playerError = "This player can't read the document: ${it.message}" },
    )
  }
  val showing = state.bytes != null && playerError == null
  overlay(
    PreviewOverlayState(
      label = label,
      mode = if (showing) mode else null,
      loading = !state.loaded,
      error = playerError ?: state.failure.takeIf { state.bytes == null && state.signIn == null },
      notice = state.failure.takeIf { state.bytes != null && state.signIn == null },
      action = action,
      signIn = state.signIn,
    )
  )
}

@Composable
private fun DesignContent(
  preview: DesignPreview,
  devices: MutableStateFlow<DeviceProfile?>,
  label: String,
  device: DeviceProfile?,
  stream: @Composable (SessionLink, DeviceProfile) -> Unit,
  overlay: @Composable (PreviewOverlayState) -> Unit,
  action: String?,
  onAction: (Int, Any?) -> Unit,
) {
  LaunchedEffect(preview) { preview.run(devices.filterNotNull()) }
  val state by preview.state.collectAsState()
  var playerError by remember(state.content) { mutableStateOf<String?>(null) }

  when (val content = state.content) {
    is PreviewContent.Document ->
      RemoteDocumentPlayer(
        content.bytes,
        onAction = onAction,
        onError = { playerError = "This player can't read the design's document: ${it.message}" },
      )
    is PreviewContent.Stream -> device?.let { stream(content.link, it) }
    is PreviewContent.Still -> {
      val bitmap =
        remember(content) {
          BitmapFactory.decodeByteArray(content.png, 0, content.png.size)?.asImageBitmap()
        }
      if (bitmap != null) {
        Image(
          bitmap,
          contentDescription = "Design render",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Fit,
        )
      }
    }
    is PreviewContent.Failed,
    is PreviewContent.NeedsSignIn,
    PreviewContent.Loading -> Unit
  }
  overlay(state.toOverlay(label, playerError, action))
}

private fun DesignPreviewState.toOverlay(label: String, playerError: String?, action: String?) =
  PreviewOverlayState(
    label = label,
    mode =
      when (val c = content) {
        is PreviewContent.Document ->
          if (c.fitted) "Playing on device · compiled for this screen"
          else "Playing on device · as authored"
        is PreviewContent.Stream -> "Native render · streamed"
        is PreviewContent.Still -> "Native render · still"
        else -> null
      }?.let { mode -> revisionOf(content)?.let { "$mode · r$it" } ?: mode },
    loading = content is PreviewContent.Loading || reloading,
    error = playerError ?: (content as? PreviewContent.Failed)?.message,
    signIn =
      (content as? PreviewContent.NeedsSignIn)?.let { SignInNeeded(it.server, it.status, it.signedIn) },
    notice =
      notice
        ?: (content as? PreviewContent.Stream)?.warnings?.takeIf { it.isNotEmpty() }?.joinToString()
        ?: (content as? PreviewContent.Still)?.reasons?.takeIf { it.isNotEmpty() }?.joinToString(),
    following = following,
    action = action,
  )

private fun revisionOf(content: PreviewContent): Long? =
  when (content) {
    is PreviewContent.Document -> content.revision
    is PreviewContent.Stream -> content.revision
    is PreviewContent.Still -> content.revision
    else -> null
  }
