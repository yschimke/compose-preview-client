package ee.schimke.composeai.clients.preview

import ee.schimke.composeai.clients.SessionLink
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A server's document lane (`POST /docs` → `/d/<id>`): hands one of your own `.rc` documents to the
 * server and gets back an expiring permalink that plays it — in a browser, on another device, and
 * in a UI-builder design, whose `remote-compose/document` node takes exactly this URL as its
 * `documentUrl`.
 *
 * Publishing is not something a sign-in grant can do: the server's ingest gate refuses grants
 * outright. A `--public` server takes anonymous uploads; a private one needs its operator token. So
 * [operatorToken] is that token or nothing — never a grant.
 */
class DocsApi(
  private val server: ServerAddress,
  private val operatorToken: String?,
  private val client: HttpClient = sharedClient,
) {

  suspend fun upload(name: String, bytes: ByteArray): Upload {
    val response =
      client.post("${server.httpBase}/docs?name=${SessionLink.encodeQuery(name)}") {
        operatorToken?.let { header(UiBuilderApi.TOKEN_HEADER, it) }
        contentType(ContentType.Application.OctetStream)
        setBody(bytes)
      }
    val text = response.bodyAsText()
    if (!response.status.isSuccess()) {
      return Upload.Refused(
        when (response.status.value) {
          // The ingest gate answers 404 rather than 401, so the route's existence isn't disclosed.
          404 ->
            "${server.label} doesn't take uploads from this device (it needs its operator token)."
          413 -> "The document is too large for ${server.label}."
          else ->
            text.lineSequence().firstOrNull { it.isNotBlank() } ?: "HTTP ${response.status.value}"
        }
      )
    }
    val accepted =
      runCatching { json.decodeFromString(Accepted.serializer(), text) }.getOrNull()
        ?: return Upload.Refused("unreadable reply from ${server.label}")
    return Upload.Published(
      permalink = server.httpBase + accepted.url,
      expiresIn = accepted.expiresIn,
      format = accepted.format,
    )
  }

  sealed interface Upload {
    /**
     * [permalink] plays the document; paste it into a `remote-compose/document` node to embed it.
     */
    data class Published(val permalink: String, val expiresIn: String?, val format: String?) :
      Upload

    data class Refused(val message: String) : Upload
  }

  /** `POST /docs`'s reply. Unknown fields are ignored. */
  @Serializable
  internal data class Accepted(
    val id: String,
    val url: String,
    val name: String? = null,
    val format: String? = null,
    val expiresIn: String? = null,
  )

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
    val sharedClient: HttpClient by lazy { HttpClient(OkHttp) { expectSuccess = false } }
  }
}
