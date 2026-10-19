package ee.schimke.composeai.clients.preview

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A Remote Compose document served over HTTP — a `.rc` on a catalog, a dev server, a CI artifact.
 * [watch] keeps the device in step with the file: it re-fetches on an interval with the validators
 * the server gave (`ETag` / `Last-Modified`), so an unchanged document costs a 304, and only emits
 * when the bytes actually change.
 */
class RemoteDocumentSource(
  private val url: String,
  private val token: String?,
  private val client: HttpClient = sharedClient,
) {
  private var etag: String? = null
  private var lastModified: String? = null
  private var digest: String? = null

  /** One conditional fetch. */
  suspend fun fetch(): Result {
    val response =
      client.get(url) {
        token?.let { header(UiBuilderApi.TOKEN_HEADER, it) }
        etag?.let { header(HttpHeaders.IfNoneMatch, it) }
        lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
      }
    if (response.status == HttpStatusCode.NotModified) return Result.Unchanged
    if (!response.status.isSuccess()) {
      val body = runCatching { response.bodyAsText() }.getOrDefault("").take(500)
      return Result.Failed(
        "HTTP ${response.status.value}${if (body.isBlank()) "" else ": $body"}",
        response.status.value,
      )
    }
    etag = response.headers[HttpHeaders.ETag]
    lastModified = response.headers[HttpHeaders.LastModified]
    val bytes = response.bodyAsBytes()
    val sha = sha256(bytes)
    if (sha == digest) return Result.Unchanged
    digest = sha
    return Result.Changed(bytes)
  }

  /**
   * The document now, then again whenever it changes, until cancelled. A failed poll after the
   * first document is reported but keeps the last good one on screen.
   */
  fun watch(
    interval: Duration = DEFAULT_INTERVAL,
    unvalidatedInterval: Duration = UNVALIDATED_INTERVAL,
  ): Flow<Result> = flow {
    while (true) {
      val result =
        try {
          fetch()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
      if (result !is Result.Unchanged) emit(result)
      // Without validators every poll downloads the whole document, so poll less often.
      delay(if (etag == null && lastModified == null) unvalidatedInterval else interval)
    }
  }

  sealed interface Result {
    class Changed(val bytes: ByteArray) : Result

    data object Unchanged : Result

    /** [status] is the HTTP status when the server answered; 401/403 mean sign in. */
    data class Failed(val message: String, val status: Int? = null) : Result
  }

  companion object {
    val DEFAULT_INTERVAL: Duration = 2.seconds

    /**
     * For a server that sends no `ETag` / `Last-Modified` (a hosted catalog's `render/<id>.rc`),
     * where each poll is a full download.
     */
    val UNVALIDATED_INTERVAL: Duration = 10.seconds

    private val sharedClient: HttpClient by lazy { HttpClient(OkHttp) { expectSuccess = false } }

    internal fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
