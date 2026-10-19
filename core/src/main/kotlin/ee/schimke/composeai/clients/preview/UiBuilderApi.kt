package ee.schimke.composeai.clients.preview

import ee.schimke.composeai.clients.SessionLink
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The slice of a UI builder's HTTP surface (`compose-preview serve`, `/api/ui-builder/v1/…`) a
 * device preview needs: read a design, compile a design document to Remote Compose for this device,
 * render it natively when it can't be, and hear about edits. Each call is one request; the policy
 * of which to try lives in [DesignPreview].
 *
 * Credentials ride the `X-Compose-Preview-Token` header (the operator token or a short-lived agent
 * grant), never a logged URL, except on the WebSocket where the browser-compatible `?token=` is the
 * documented form.
 */
class UiBuilderApi(
  private val server: ServerAddress,
  private val token: String?,
  private val client: HttpClient = sharedClient,
) {

  /** The design's document (`DesignDocumentV1`) as JSON, kept untyped so unknown fields survive. */
  suspend fun design(designId: String, revision: Long? = null): Fetch<JsonObject> {
    val response = client.get(designUrl(designId, "", revision)) { authorize() }
    if (!response.status.isSuccess()) return response.refusal()
    return runCatching { Fetch.Ok(json.parseToJsonElement(response.bodyAsText()).jsonObject) }
      .getOrElse { Fetch.Refused(response.status.value, "design is not a JSON document") }
  }

  /**
   * Compile [document] — which need not be saved — to Remote Compose bytes. A 422 carries the
   * generator's reasons (the design holds something a document can't express); a 404/405 means this
   * host or the design's catalog has no Remote Compose export.
   */
  suspend fun compileDocument(document: JsonObject): Fetch<ByteArray> =
    postDocument("${server.httpBase}$DOCUMENTS/export.rc", document)

  /** A still PNG of [document] at its own environment — the last resort for an unsaved design. */
  suspend fun renderDocumentPng(document: JsonObject): Fetch<ByteArray> =
    postDocument("${server.httpBase}$DOCUMENTS/export.png", document)

  /** The saved design's Remote Compose export exactly as authored. */
  suspend fun exportRc(designId: String, revision: Long? = null): Fetch<ByteArray> {
    val response = client.get(designUrl(designId, "/export.rc", revision)) { authorize() }
    return if (response.status.isSuccess()) Fetch.Ok(response.bodyAsBytes()) else response.refusal()
  }

  /** The saved design rendered to a PNG at its own (authored) environment. */
  suspend fun exportPng(designId: String, revision: Long? = null): Fetch<ByteArray> {
    val response = client.get(designUrl(designId, "/export.png", revision)) { authorize() }
    return if (response.status.isSuccess()) Fetch.Ok(response.bodyAsBytes()) else response.refusal()
  }

  /**
   * Compile and render the design with real Compose on the host. The reply names the live session
   * its frames stream from, when the host has one.
   */
  suspend fun nativePreview(
    designId: String,
    revision: Long? = null,
    hostShape: String? = null,
  ): Fetch<NativePreview> {
    val response =
      client.post(designUrl(designId, "/native-preview", revision)) {
        authorize()
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { hostShape?.let { put("hostShape", it) } }.toString())
      }
    if (!response.status.isSuccess()) return response.refusal()
    return runCatching {
        Fetch.Ok(json.decodeFromString(NativePreview.serializer(), response.bodyAsText()))
      }
      .getOrElse { Fetch.Refused(response.status.value, "unreadable native render reply") }
  }

  /**
   * One element per pushed design update (revision, presence, …), as raw JSON text. The flow
   * completes when the server closes the socket and throws on a failed handshake; [DesignPreview]
   * reconnects.
   */
  fun updates(designId: String): Flow<String> = callbackFlow {
    val url = buildString {
      append(server.httpBase.replaceFirst("http", "ws"))
      append(DESIGNS).append('/').append(SessionLink.encodeSegment(designId)).append("/updates")
      token?.let { append("?token=").append(SessionLink.encodeQuery(it)) }
    }
    val job = launch {
      try {
        client.webSocket(urlString = url, request = { authorize() }) {
          for (frame in incoming) if (frame is Frame.Text) send(frame.readText())
        }
        close()
      } catch (e: Throwable) {
        close(e)
      }
    }
    awaitClose { job.cancel() }
  }

  private suspend fun postDocument(url: String, document: JsonObject): Fetch<ByteArray> {
    val response =
      client.post(url) {
        authorize()
        contentType(ContentType.Application.Json)
        setBody(document.toString())
      }
    return if (response.status.isSuccess()) Fetch.Ok(response.bodyAsBytes()) else response.refusal()
  }

  private fun designUrl(designId: String, suffix: String, revision: Long?): String = buildString {
    append(server.httpBase).append(DESIGNS).append('/')
    append(SessionLink.encodeSegment(designId)).append(suffix)
    revision?.let { append("?revision=").append(it) }
  }

  private fun HttpRequestBuilder.authorize() {
    token?.let { header(TOKEN_HEADER, it) }
    header(HttpHeaders.CacheControl, "no-store")
  }

  private suspend fun <T> HttpResponse.refusal(): Fetch<T> =
    Fetch.Refused(status.value, runCatching { bodyAsText() }.getOrDefault("").take(2_000))

  /** One call's answer: the value, or the server's refusal with its status and body. */
  sealed interface Fetch<out T> {
    data class Ok<T>(val value: T) : Fetch<T>

    data class Refused(val status: Int, val message: String) : Fetch<Nothing> {
      /** The route or format isn't offered here — a reason to try the next lane, not an error. */
      val unsupported: Boolean
        get() = status == 404 || status == 405 || status == 501

      /** The design can't be expressed in this format (the generator's verdict). */
      val inexpressible: Boolean
        get() = status == 422

      val unauthorized: Boolean
        get() = status == 401 || status == 403
    }
  }

  /** `compose-preview/ui-builder-native-preview/v1`. Unknown fields are ignored. */
  @Serializable
  data class NativePreview(
    val designId: String,
    val revision: Long,
    val imageBase64: String? = null,
    val compileError: String? = null,
    val warnings: List<String> = emptyList(),
    val live: Live? = null,
  ) {
    @Serializable data class Live(val sessionId: String, val previewId: String)
  }

  companion object {
    const val TOKEN_HEADER: String = "X-Compose-Preview-Token"
    private const val DESIGNS = "/api/ui-builder/v1/designs"
    private const val DOCUMENTS = "/api/ui-builder/v1/documents"

    internal val json = Json {
      ignoreUnknownKeys = true
      isLenient = true
    }

    private val sharedClient: HttpClient by lazy {
      HttpClient(OkHttp) {
        install(WebSockets) { pingIntervalMillis = 20_000 }
        expectSuccess = false
      }
    }
  }
}
