package ee.schimke.composeai.clients.preview

import ee.schimke.composeai.clients.SessionLink
import ee.schimke.composeai.clients.SessionTarget
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * What a device preview is showing, from most to least real.
 *
 * - [Document] — Remote Compose bytes the device **plays itself**. When [fitted], the design was
 *   compiled for this device's window, density, font scale, theme and locale; otherwise it is the
 *   export as authored, and the player scales it.
 * - [Stream] — the design rendered by real Compose on the host and streamed as frames, with the
 *   device's metrics sent as overrides and touches forwarded back.
 * - [Still] — one frame, when the host can render but not stream.
 */
sealed interface PreviewContent {
  data object Loading : PreviewContent

  class Document(val bytes: ByteArray, val revision: Long?, val fitted: Boolean) : PreviewContent {
    val digest: String = RemoteDocumentSource.sha256(bytes)

    override fun equals(other: Any?): Boolean =
      other is Document &&
        other.digest == digest &&
        other.revision == revision &&
        other.fitted == fitted

    override fun hashCode(): Int = digest.hashCode()
  }

  data class Stream(val link: SessionLink, val revision: Long?, val warnings: List<String>) :
    PreviewContent

  class Still(val png: ByteArray, val revision: Long?, val reasons: List<String>) : PreviewContent

  data class Failed(val message: String) : PreviewContent
}

/**
 * The preview's state: what is on screen, plus a [notice] for something that went wrong *after* it
 * — a failed reload keeps the last good content up rather than blanking a device mid-review.
 */
data class DesignPreviewState(
  val content: PreviewContent = PreviewContent.Loading,
  val notice: String? = null,
  /** Edits on the server reach this device as they're made. */
  val following: Boolean = false,
  val reloading: Boolean = false,
)

/**
 * Drives a device preview of a `.uid` design.
 *
 * Each load tries, in order:
 * 1. **Compile for this device.** Read the design, point its `environment` at the device
 *    ([DeviceProfile.applyTo]) and compile it to Remote Compose — so the device plays a document
 *    laid out for its own screen, not a scaled screenshot of someone else's.
 * 2. **The authored export** (`export.rc`) when the host can't take a document (an older host).
 * 3. **The native render**, streamed, when the design's catalog has no Remote Compose lowering (a
 *    Material 3 screen, say).
 *
 * [run] then follows the design: every revision pushed on the design's `updates` socket reloads it,
 * and a change of device (rotation, font scale, dark mode) recompiles the document. A stream
 * doesn't need recompiling for that — the UI re-sends the overrides on the open lane.
 */
class DesignPreview
internal constructor(
  private val source: Source,
  private val api: UiBuilderApi,
  private val updates: (() -> Flow<String>)?,
  private val debounce: Duration,
  private val reconnectDelay: Duration,
) {
  constructor(
    link: PreviewLink.Design,
    api: UiBuilderApi = UiBuilderApi(link.server, link.token),
  ) : this(
    Source.Hosted(link),
    api,
    // A pinned revision never changes, so there's nothing to follow.
    if (link.revision == null) ({ api.updates(link.designId) }) else null,
    DEFAULT_DEBOUNCE,
    DEFAULT_RECONNECT,
  )

  /** A `.uid` file's [document], compiled by the server in [link]. */
  constructor(
    link: PreviewLink.DesignFile,
    document: JsonObject,
    api: UiBuilderApi = UiBuilderApi(link.server, link.token),
  ) : this(
    Source.Local(link.server, link.token, document),
    api,
    null,
    DEFAULT_DEBOUNCE,
    DEFAULT_RECONNECT,
  )

  private val _state = MutableStateFlow(DesignPreviewState())
  val state: StateFlow<DesignPreviewState> = _state.asStateFlow()

  @Volatile private var loadedRevision: Long? = null

  /** Load for each [device] and on every pushed edit, until cancelled. */
  suspend fun run(device: Flow<DeviceProfile>) = coroutineScope {
    val edits = Channel<Unit>(Channel.CONFLATED)
    updates?.let { open -> launch { follow(open, edits) } }
    var editCount = 0L
    var lastLoadedEdit = -1L
    // Numbered so a device change (which re-emits the same number) is told apart from an edit.
    val editIds = edits.receiveAsFlow().onStart { emit(Unit) }.map { ++editCount }
    combine(device.distinctUntilChanged(), editIds) { profile, edit -> profile to edit }
      .collectLatest { (profile, edit) ->
        val showing = _state.value.content
        // A device change on an open stream is the UI's to send as overrides; recompiling would
        // just restart the same render.
        if (showing is PreviewContent.Stream && edit == lastLoadedEdit) return@collectLatest
        if (showing !is PreviewContent.Loading) delay(debounce)
        reload(profile)
        lastLoadedEdit = edit
      }
  }

  /** One load for [device], reported through [state]. */
  suspend fun reload(device: DeviceProfile) {
    _state.update { it.copy(reloading = true) }
    val next =
      try {
        load(device)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        PreviewContent.Failed(e.message ?: e::class.simpleName.orEmpty())
      }
    _state.update { current ->
      val keepShowing =
        next is PreviewContent.Failed &&
          current.content !is PreviewContent.Loading &&
          current.content !is PreviewContent.Failed
      if (keepShowing)
        current.copy(notice = (next as PreviewContent.Failed).message, reloading = false)
      else current.copy(content = next, notice = null, reloading = false)
    }
  }

  internal suspend fun load(device: DeviceProfile): PreviewContent {
    val reasons = mutableListOf<String>()
    val (document, revision) =
      when (source) {
        is Source.Local -> source.document to source.document.revision()
        is Source.Hosted ->
          when (val read = api.design(source.link.designId, source.link.revision)) {
            is UiBuilderApi.Fetch.Ok -> read.value to read.value.revision()
            is UiBuilderApi.Fetch.Refused -> {
              if (read.unauthorized) return refusedAccess(read)
              // Every host with the export lanes serves the design route, so a 404 here is the
              // design, not the route: say so rather than falling through to an auth failure.
              if (read.status == 404) {
                return PreviewContent.Failed(
                  "There's no design “${source.link.designId}” on ${source.link.server.label}."
                )
              }
              if (!read.unsupported) reasons += "read: ${read.describe()}"
              null to null
            }
          }
      }
    loadedRevision = revision

    if (document != null) {
      when (val compiled = api.compileDocument(document.forDevice(device))) {
        is UiBuilderApi.Fetch.Ok ->
          return PreviewContent.Document(compiled.value, revision, fitted = true)
        is UiBuilderApi.Fetch.Refused -> {
          if (compiled.unauthorized) return refusedAccess(compiled)
          if (!compiled.unsupported) reasons += "Remote Compose: ${compiled.describe()}"
        }
      }
    }

    when (source) {
      is Source.Local -> {
        val document = source.document.forDevice(device)
        return when (val png = api.renderDocumentPng(document)) {
          is UiBuilderApi.Fetch.Ok -> PreviewContent.Still(png.value, revision, reasons)
          is UiBuilderApi.Fetch.Refused ->
            PreviewContent.Failed(
              (reasons + "render: ${png.describe()}").joinToString("\n").ifBlank {
                "This server can't render .uid files"
              }
            )
        }
      }
      is Source.Hosted -> {
        val link = source.link
        if (document == null) {
          when (val authored = api.exportRc(link.designId, link.revision)) {
            is UiBuilderApi.Fetch.Ok ->
              return PreviewContent.Document(authored.value, revision, fitted = false)
            is UiBuilderApi.Fetch.Refused ->
              if (!authored.unsupported) reasons += "export.rc: ${authored.describe()}"
          }
        }
        return when (val native = api.nativePreview(link.designId, link.revision)) {
          is UiBuilderApi.Fetch.Ok -> native.value.toContent(link, reasons)
          is UiBuilderApi.Fetch.Refused -> {
            if (native.unauthorized) return refusedAccess(native)
            reasons += "native render: ${native.describe()}"
            PreviewContent.Failed(reasons.joinToString("\n"))
          }
        }
      }
    }
  }

  private fun UiBuilderApi.NativePreview.toContent(
    link: PreviewLink.Design,
    reasons: List<String>,
  ): PreviewContent {
    live?.let {
      val stream =
        SessionLink(
          host = link.server.host,
          port = link.server.port,
          token = link.token.orEmpty(),
          target = SessionTarget.Preview(it.previewId, it.sessionId),
          secure = link.server.secure,
        )
      return PreviewContent.Stream(stream, revision, warnings)
    }
    val png = imageBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    if (png != null) return PreviewContent.Still(png, revision, reasons + warnings)
    return PreviewContent.Failed(
      (reasons + listOfNotNull(compileError ?: "the host rendered no frame")).joinToString("\n")
    )
  }

  /** Follow the design's update socket, reconnecting until cancelled. */
  private suspend fun follow(open: () -> Flow<String>, edits: Channel<Unit>) {
    while (true) {
      try {
        _state.update { it.copy(following = true) }
        open().collect { message -> if (isEdit(message, loadedRevision)) edits.trySend(Unit) }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Reconnect below; the last good content stays on screen.
      }
      _state.update { it.copy(following = false) }
      delay(reconnectDelay)
      // Anything may have changed while the socket was down.
      edits.trySend(Unit)
    }
  }

  internal sealed interface Source {
    data class Hosted(val link: PreviewLink.Design) : Source

    data class Local(val server: ServerAddress, val token: String?, val document: JsonObject) :
      Source
  }

  companion object {
    val DEFAULT_DEBOUNCE: Duration = 250.milliseconds
    val DEFAULT_RECONNECT: Duration = 3.seconds

    private fun refusedAccess(refusal: UiBuilderApi.Fetch.Refused) =
      PreviewContent.Failed(
        "The server refused access (HTTP ${refusal.status}). Open the design's link with a token, " +
          "or ask its owner to share it."
      )

    private fun UiBuilderApi.Fetch.Refused.describe(): String =
      message.lineSequence().firstOrNull { it.isNotBlank() }?.take(300) ?: "HTTP $status"

    private fun JsonObject.revision(): Long? = (this["revision"] as? JsonPrimitive)?.longOrNull

    internal fun JsonObject.forDevice(device: DeviceProfile): JsonObject = buildJsonObject {
      forEach { (key, value) -> put(key, value) }
      put("environment", device.applyTo(this@forDevice["environment"] as? JsonObject))
    }

    /**
     * Whether an `updates` frame can have changed what the design draws. Presence and operation
     * outcomes can't, and neither can a revision this device already shows; anything else (a delta,
     * a snapshot, a shape this client hasn't met) reloads.
     */
    internal fun isEdit(message: String, loadedRevision: Long?): Boolean {
      val root =
        runCatching { UiBuilderApi.json.parseToJsonElement(message).jsonObject }.getOrNull()
          ?: return true
      val kinds = buildList { collectKinds(root, depth = 0) }
      if (kinds.any { "presence" in it || "outcome" in it }) return false
      val revision = findRevision(root, depth = 0)
      return revision == null || loadedRevision == null || revision > loadedRevision
    }

    private fun MutableList<String>.collectKinds(element: JsonElement, depth: Int) {
      if (depth > 2 || element !is JsonObject) return
      for (key in listOf("type", "kind")) {
        (element[key] as? JsonPrimitive)
          ?.takeIf { it.isString }
          ?.let { add(it.content.lowercase()) }
      }
      element.values.forEach { collectKinds(it, depth + 1) }
    }

    private fun findRevision(element: JsonElement, depth: Int): Long? {
      if (depth > 3 || element !is JsonObject) return null
      (element["revision"] as? JsonPrimitive)?.longOrNull?.let {
        return it
      }
      return element.values.firstNotNullOfOrNull { findRevision(it, depth + 1) }
    }
  }
}
