package ee.schimke.composeai.clients.preview

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RemoteDocumentSourceTest {

  @Test
  fun conditionalFetchSendsValidatorsAndReportsOnlyChanges() =
    runBlocking<Unit> {
      val seen = mutableListOf<String?>()
      var body = byteArrayOf(1)
      val engine = MockEngine { request ->
        seen += request.headers[HttpHeaders.IfNoneMatch]
        if (
          request.headers[HttpHeaders.IfNoneMatch] == "\"v1\"" && body.contentEquals(byteArrayOf(1))
        )
          respond("", HttpStatusCode.NotModified)
        else
          respond(
            body,
            headers = headersOf(HttpHeaders.ETag, if (body[0] == 1.toByte()) "\"v1\"" else "\"v2\""),
          )
      }
      val source =
        RemoteDocumentSource("https://h/a.rc", "tok", HttpClient(engine) { expectSuccess = false })

      val first = source.fetch() as RemoteDocumentSource.Result.Changed
      assertThat(first.bytes).isEqualTo(byteArrayOf(1))
      assertThat(source.fetch()).isEqualTo(RemoteDocumentSource.Result.Unchanged)
      body = byteArrayOf(2)
      val second = source.fetch() as RemoteDocumentSource.Result.Changed
      assertThat(second.bytes).isEqualTo(byteArrayOf(2))
      assertThat(seen).containsExactly(null, "\"v1\"", "\"v1\"").inOrder()
    }

  @Test
  fun sameBytesWithoutValidatorsAreUnchanged() =
    runBlocking<Unit> {
      val engine = MockEngine { respond(byteArrayOf(7)) }
      val source =
        RemoteDocumentSource("https://h/a.rc", null, HttpClient(engine) { expectSuccess = false })
      assertThat(source.fetch()).isInstanceOf(RemoteDocumentSource.Result.Changed::class.java)
      assertThat(source.fetch()).isEqualTo(RemoteDocumentSource.Result.Unchanged)
    }

  @Test
  fun watchReportsFailuresAndKeepsPolling() =
    runBlocking<Unit> {
      var calls = 0
      val engine = MockEngine {
        calls++
        if (calls == 2) respond("gone", HttpStatusCode.BadGateway)
        else respond(byteArrayOf(calls.toByte()))
      }
      val source =
        RemoteDocumentSource("https://h/a.rc", null, HttpClient(engine) { expectSuccess = false })
      val results = source.watch(1.milliseconds, 1.milliseconds).take(3).toList()
      assertThat(results[0]).isInstanceOf(RemoteDocumentSource.Result.Changed::class.java)
      assertThat(results[1]).isEqualTo(RemoteDocumentSource.Result.Failed("HTTP 502: gone", 502))
      assertThat((results[2] as RemoteDocumentSource.Result.Changed).bytes)
        .isEqualTo(byteArrayOf(3))
    }
}
