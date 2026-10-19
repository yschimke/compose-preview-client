package ee.schimke.composeai.clients.auth

import ee.schimke.composeai.clients.preview.ServerAddress
import ee.schimke.composeai.clients.preview.UiBuilderApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A short-lived credential a person approved for this app on one server. It is presented exactly
 * like the operator token (`X-Compose-Preview-Token` / `?token=`), so nothing else changes shape.
 */
@Serializable
data class AccessGrant(
  /** The server's origin, [ServerAddress.httpBase]. */
  val origin: String,
  val token: String,
  val scopes: List<String> = emptyList(),
  val capabilities: List<String> = emptyList(),
  /** Wall-clock expiry, epoch millis; null when the server didn't say. */
  val expiresAtMillis: Long? = null,
  /** Who approved it (a GitHub login, or `operator (token)`), for the UI. */
  val approvedBy: String? = null,
) {
  fun isExpired(nowMillis: Long): Boolean = expiresAtMillis != null && nowMillis >= expiresAtMillis
}

/**
 * The server's agent-access endpoints (`/agent-access/…`): an RFC 8628 device grant. The app asks
 * for access, a person opens the approval link in a browser where they are signed in (or hold the
 * operator token), checks the user code matches, and approves; the app's poll then collects a
 * scoped, expiring token. The link carries no authority — the token is only ever handed to the
 * holder of the device secret, on the poll.
 */
class AccessGrantApi(
  private val server: ServerAddress,
  private val client: HttpClient = sharedClient,
) {

  suspend fun request(
    label: String,
    scope: String = SCOPE_LIVE,
    capabilities: List<String> = PREVIEW_CAPABILITIES,
    ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  ): Answer<OpenResponse> {
    val response =
      client.post("${server.httpBase}$BASE/request") {
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            OpenRequest.serializer(),
            OpenRequest(label, scope, ttlSeconds, capabilities),
          )
        )
      }
    val text = response.bodyAsText()
    if (!response.status.isSuccess()) return Answer.Refused(response.status.value, text.take(500))
    return decode(text, OpenResponse.serializer())
  }

  /** One poll. [waitSeconds] holds it open until a decision (the server caps it at 30s). */
  suspend fun poll(
    requestId: String,
    deviceSecret: String,
    waitSeconds: Long = 0,
  ): Answer<PollResponse> {
    val response =
      client.post("${server.httpBase}$BASE/poll") {
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            PollRequest.serializer(),
            PollRequest(requestId, deviceSecret, waitSeconds),
          )
        )
      }
    val text = response.bodyAsText()
    // A poll answers its status in the body, sometimes with a non-2xx code; read it either way.
    return runCatching { Answer.Ok(json.decodeFromString(PollResponse.serializer(), text)) }
      .getOrElse { Answer.Refused(response.status.value, text.take(500)) }
  }

  /** What [token] is now — whether it's still live, and why not. Never echoes the token. */
  suspend fun whoami(token: String): Answer<WhoamiResponse> {
    val response =
      client.get("${server.httpBase}$BASE/whoami") { header(UiBuilderApi.TOKEN_HEADER, token) }
    return decode(response.bodyAsText(), WhoamiResponse.serializer())
  }

  /** Give the grant up (signing out). */
  suspend fun revoke(token: String): Boolean {
    val response =
      client.post("${server.httpBase}$BASE/revoke") { header(UiBuilderApi.TOKEN_HEADER, token) }
    return response.status.isSuccess()
  }

  private fun <T> decode(
    text: String,
    serializer: kotlinx.serialization.KSerializer<T>,
  ): Answer<T> =
    runCatching { Answer.Ok(json.decodeFromString(serializer, text)) }
      .getOrElse { Answer.Refused(200, "unreadable reply from ${server.label}") }

  sealed interface Answer<out T> {
    data class Ok<T>(val value: T) : Answer<T>

    data class Refused(val status: Int, val message: String) : Answer<Nothing>
  }

  @Serializable
  internal data class OpenRequest(
    val label: String,
    val scope: String,
    @SerialName("ttlSeconds") val ttlSeconds: Long,
    val capabilities: List<String>,
  )

  @Serializable
  data class OpenResponse(
    val requestId: String,
    val deviceSecret: String,
    val userCode: String,
    val approveUrl: String,
    val pollUrl: String? = null,
    val expiresInSeconds: Long = 1_800,
    val pollIntervalSeconds: Long = 5,
    val requestedScope: String? = null,
    val maxScope: String? = null,
    val requestedCapabilities: List<String> = emptyList(),
    val maxCapabilities: List<String> = emptyList(),
  )

  @Serializable
  internal data class PollRequest(
    val requestId: String,
    val deviceSecret: String,
    val waitSeconds: Long,
  )

  @Serializable
  data class PollResponse(
    val status: String,
    val token: String? = null,
    val scopes: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val expiresInSeconds: Long? = null,
    val approvedBy: String? = null,
    val retryAfterSeconds: Long? = null,
    val message: String? = null,
  )

  @Serializable
  data class WhoamiResponse(
    val active: Boolean,
    val scopes: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val expiresInSeconds: Long? = null,
    val approvedBy: String? = null,
    val reason: String? = null,
    val message: String? = null,
  )

  companion object {
    private const val BASE = "/agent-access"

    /** Native renders stream over the live viewer socket. */
    const val SCOPE_LIVE: String = "live"

    /**
     * Reading a design, and compiling it (`export.rc`, the native render) — never editing it. A
     * preview has no business writing to the design it shows.
     */
    val PREVIEW_CAPABILITIES: List<String> = listOf("ui-builder-read", "ui-builder-export")

    /** A working day; the server clamps it to its own ceiling (8h by default). */
    const val DEFAULT_TTL_SECONDS: Long = 8 * 60 * 60

    internal val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

    private val sharedClient: HttpClient by lazy { HttpClient(OkHttp) { expectSuccess = false } }

    /**
     * The approval page for [requestId] on [server]. Built here rather than taken from the reply's
     * `approveUrl`, so the person is only ever sent to the server the app asked — never to a
     * look-alike a hostile reply names — and a proxy that rewrites the host can't misdirect them.
     */
    fun approvalUrl(server: ServerAddress, requestId: String): String =
      "${server.httpBase}$BASE/${ee.schimke.composeai.clients.SessionLink.encodeSegment(requestId)}"
  }
}
