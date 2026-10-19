package ee.schimke.composeai.clients.preview

import ee.schimke.composeai.clients.SessionLink
import ee.schimke.composeai.clients.SessionTarget
import java.net.URI

/**
 * Where a server lives, independent of what is opened on it. The UI-builder REST routes and the
 * streamed-frame lane share one origin, so a design opened over HTTP can hand its live native
 * render to the WebSocket lane on the same address.
 */
data class ServerAddress(val host: String, val port: Int, val secure: Boolean) {
  init {
    require(host.isNotBlank()) { "host is blank" }
    require(port in 1..65535) { "port out of range: $port" }
  }

  /** `http(s)://host[:port]` — the port is omitted when it is the scheme's default. */
  val httpBase: String
    get() {
      val scheme = if (secure) "https" else "http"
      val defaultPort = if (secure) 443 else 80
      val hostPart = if (':' in host && !host.startsWith("[")) "[$host]" else host
      return if (port == defaultPort) "$scheme://$hostPart" else "$scheme://$hostPart:$port"
    }

  val label: String
    get() = if (port == 443 || port == 80) host else "$host:$port"

  companion object {
    fun of(uri: URI): ServerAddress? {
      val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
      val secure = uri.scheme.equals("https", true) || uri.scheme.equals("wss", true)
      val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
      return runCatching { ServerAddress(host, port, secure) }.getOrNull()
    }
  }
}

/**
 * Anything the app can open as a device preview. A tapped or pasted link resolves to one of:
 * - [Design] — a `.uid` design hosted by a UI builder (`https://host/ui-builder/<designId>`).
 * - [DesignFile] — a `.uid` document on this device, compiled by a UI builder [server].
 * - [RemoteDocument] — a Remote Compose `.rc` document at an `http(s)` URL.
 * - [LocalDocument] — a `.rc` file on this device (`content://` / `file://`).
 * - [Stream] — a running `compose-preview serve` preview streamed as frames (the original lane).
 *
 * `.rc` documents and designs that export to Remote Compose are **played on the device** by the
 * AndroidX Remote Compose player — the same bytes a host app would ship, at the device's real size,
 * density, font scale and theme. Only designs that cannot be expressed as a document fall back to
 * frames streamed from the server's native Compose render.
 */
sealed interface PreviewLink {
  /** A short, human-facing label for the status chrome. */
  val label: String

  data class Stream(val session: SessionLink) : PreviewLink {
    override val label: String
      get() = session.target.label
  }

  data class Design(
    val server: ServerAddress,
    val designId: String,
    /** The operator token or agent grant; null for a public design. */
    val token: String? = null,
    /** A pinned revision; null follows the head live. */
    val revision: Long? = null,
  ) : PreviewLink {
    init {
      require(designId.isNotBlank()) { "designId is blank" }
    }

    override val label: String
      get() = designId

    /** The UI builder's own permalink for this design. */
    val editorUrl: String
      get() = "${server.httpBase}/ui-builder/${SessionLink.encodeSegment(designId)}"
  }

  data class DesignFile(
    /** The `.uid` file's location, read by the app (`content://`, `file://`). */
    val uri: String,
    val server: ServerAddress,
    val token: String? = null,
  ) : PreviewLink {
    override val label: String
      get() = displayName(uri)
  }

  data class RemoteDocument(val url: String, val token: String? = null) : PreviewLink {
    override val label: String
      get() = displayName(url)
  }

  data class LocalDocument(val uri: String) : PreviewLink {
    override val label: String
      get() = displayName(uri)
  }

  /**
   * One preview in a hosted catalog (`https://H/<catalog>/p/<id>`). The catalog serves each
   * preview's render as `…/render/<id>.png` and, for Remote Compose previews, the document itself
   * as `…/render/<id>.rc` — which the device plays. Other previews stream live (signed in) or show
   * the PNG.
   */
  data class CatalogPreview(
    val server: ServerAddress,
    /** The catalog (the server's session), or null for the server's default one. */
    val catalog: String?,
    val previewId: String,
    val token: String? = null,
  ) : PreviewLink {
    init {
      require(previewId.isNotBlank()) { "previewId is blank" }
    }

    override val label: String
      get() = previewId

    private val base: String
      get() = server.httpBase + catalog?.let { "/" + SessionLink.encodeSegment(it) }.orEmpty()

    /** The preview's Remote Compose document, when it is one. */
    val rcUrl: String
      get() = "$base/render/${SessionLink.encodeSegment(previewId)}.rc"

    /** The preview's baked render. */
    val pngUrl: String
      get() = "$base/render/${SessionLink.encodeSegment(previewId)}.png"

    /** The preview's live frame stream, which needs a credential. */
    fun stream(token: String): SessionLink =
      SessionLink(
        server.host,
        server.port,
        token,
        SessionTarget.Preview(previewId, catalog),
        server.secure,
      )
  }

  /** The server a credential for this link belongs to; null for a file on this device. */
  val authServer: ServerAddress?
    get() =
      when (this) {
        is Design -> server
        is DesignFile -> server
        is RemoteDocument -> runCatching { ServerAddress.of(URI(url)) }.getOrNull()
        is CatalogPreview -> server
        is Stream ->
          runCatching { ServerAddress(session.host, session.port, session.secure) }.getOrNull()
        is LocalDocument -> null
      }

  /** The credential this link carries, if any. */
  val authToken: String?
    get() =
      when (this) {
        is Design -> token
        is DesignFile -> token
        is RemoteDocument -> token
        is CatalogPreview -> token
        is Stream -> session.token.ifEmpty { null }
        is LocalDocument -> null
      }

  /** This link presenting [token] instead (a grant from signing in). */
  fun withToken(token: String): PreviewLink =
    when (this) {
      is Design -> copy(token = token)
      is DesignFile -> copy(token = token)
      is RemoteDocument -> copy(token = token)
      is CatalogPreview -> copy(token = token)
      is Stream -> copy(session = session.copy(token = token))
      is LocalDocument -> this
    }

  companion object {
    const val RC_EXTENSION: String = ".rc"
    const val UID_EXTENSION: String = ".uid"

    /**
     * Parse a link into something openable, or null. Never throws.
     *
     * Shapes, most specific first:
     * - `composeai://design?host=H[&port=P][&secure=true]&id=<designId>[&token=T][&revision=N]`
     * - `composeai://rc?src=<url>[&token=T]`
     * - `http(s)://H/ui-builder/[<catalog>/]<designId>[?token=T&revision=N]` — the editor
     *   permalink, and `…/api/ui-builder/v1/designs/<designId>/…` (any design route, including its
     *   `export.rc`, so a copied export link still follows edits).
     * - `http(s)://H/[<catalog>/]p/<id>` and `…/render/<id>.png` — a catalog preview, played from
     *   its `.rc` when it has one.
     * - `http(s)://H/d/<id>` — a document someone uploaded (`POST /docs`), played from `/raw`.
     * - `http(s)://H/…/<name>.rc[?token=T]` — any served Remote Compose document.
     * - `content://…` / `file://…` — a local `.rc` (a `.uid` needs a server; see [DesignFile]).
     * - every [SessionLink] shape — a running preview's frame stream.
     */
    fun parse(raw: String?): PreviewLink? {
      val text = raw?.trim().orEmpty()
      if (text.isEmpty()) return null
      val uri = runCatching { URI(text) }.getOrNull() ?: return null
      val q = SessionLink.queryParams(uri.rawQuery)
      val token = q["token"]?.takeIf { it.isNotBlank() }
      return when (uri.scheme?.lowercase()) {
        SessionLink.SCHEME ->
          when (uri.host?.lowercase()) {
            "design" -> parseCustomDesign(q, token)
            "rc" -> q["src"]?.takeIf { it.isNotBlank() }?.let { RemoteDocument(it, token) }
            else -> SessionLink.parse(text)?.let(::Stream)
          }
        "http",
        "https" ->
          designFromPath(uri, token, q)
            ?: uploadedDocument(uri, token)
            ?: text.takeIf { isRcPath(uri.rawPath) }?.let { RemoteDocument(it, token) }
            ?: catalogPreview(uri, token)
            ?: SessionLink.parse(text)?.let(::Stream)
        "ws",
        "wss" -> SessionLink.parse(text)?.let(::Stream)
        "content",
        "file" -> LocalDocument(text)
        else -> null
      }
    }

    private fun parseCustomDesign(q: Map<String, String>, token: String?): Design? {
      val host = q["host"]?.takeIf { it.isNotBlank() } ?: return null
      val id = q["id"]?.takeIf { it.isNotBlank() } ?: return null
      val secure = q["secure"].equals("true", ignoreCase = true)
      val port = q["port"]?.toIntOrNull() ?: if (secure) 443 else SessionLink.DEFAULT_PORT
      return runCatching {
          Design(ServerAddress(host, port, secure), id, token, q["revision"]?.toLongOrNull())
        }
        .getOrNull()
    }

    private fun designFromPath(uri: URI, token: String?, q: Map<String, String>): Design? {
      val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
      val designId =
        apiDesignId(segments)
          ?: segments
            .indexOf("ui-builder")
            .takeIf { it >= 0 && it < segments.lastIndex }
            ?.let { segments.last() }
          ?: return null
      val server = ServerAddress.of(uri) ?: return null
      val decoded = SessionLink.decode(designId).takeIf { it.isNotBlank() } ?: return null
      return Design(server, decoded, token, q["revision"]?.toLongOrNull())
    }

    /** `…/api/ui-builder/v1/designs/<id>[/…]` → `<id>`. */
    private fun apiDesignId(segments: List<String>): String? {
      val api = segments.indexOf("api")
      if (api < 0 || segments.getOrNull(api + 1) != "ui-builder") return null
      val designs = segments.indexOf("designs")
      return segments.getOrNull(designs + 1)?.takeIf { designs > api }
    }

    /** `/d/<id>` (the page) or `/d/<id>/raw` (the bytes): the document's raw bytes. */
    private fun uploadedDocument(uri: URI, token: String?): RemoteDocument? {
      val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
      if (segments.size !in 2..3 || segments[0] != "d") return null
      if (segments.size == 3 && segments[2] != "raw") return null
      val server = ServerAddress.of(uri) ?: return null
      return RemoteDocument("${server.httpBase}/d/${segments[1]}/raw", token)
    }

    /** `[/<catalog>]/p/<id>` or `[/<catalog>]/render/<id>.png`. */
    private fun catalogPreview(uri: URI, token: String?): CatalogPreview? {
      val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
      val at = segments.indexOfFirst { it == "p" || it == "render" }
      if (at !in 0..1 || segments.size != at + 2) return null
      val raw = segments[at + 1]
      val id =
        SessionLink.decode(if (segments[at] == "render") raw.removeSuffix(".png") else raw).takeIf {
          it.isNotBlank() && !(segments[at] == "render" && !raw.endsWith(".png"))
        } ?: return null
      val catalog = segments.getOrNull(at - 1)?.let(SessionLink::decode)
      val server = ServerAddress.of(uri) ?: return null
      return CatalogPreview(server, catalog, id, token)
    }

    private fun isRcPath(rawPath: String?): Boolean =
      rawPath.orEmpty().endsWith(RC_EXTENSION, ignoreCase = true)

    internal fun displayName(location: String): String {
      val path = runCatching { URI(location).path }.getOrNull() ?: location
      // An uploaded document's bytes are at `/d/<id>/raw`; name it by its id, not "raw".
      val trimmed = path.trimEnd('/').removeSuffix("/raw")
      return SessionLink.decode(trimmed.substringAfterLast('/')).ifBlank { location }
    }
  }
}
