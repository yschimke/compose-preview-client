package ee.schimke.composeai.clients.preview

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DocsApiTest {

  private val server = ServerAddress("preview.coo.ee", 443, true)

  @Test
  fun uploadAnswersWithAPermalinkTheBuilderCanEmbed() =
    runBlocking<Unit> {
      var seen: HttpRequestData? = null
      var body = byteArrayOf()
      val engine = MockEngine { request ->
        seen = request
        body = request.body.toByteArray()
        respond(
          """{"id":"AbC123","name":"card.rc","format":"Remote Compose","formatId":"rc",
             "bytes":3,"url":"/d/AbC123","expiresIn":"1 hour","expiresAtEpochSeconds":1}""",
          HttpStatusCode.Created,
          headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
      val upload =
        DocsApi(server, operatorToken = null, HttpClient(engine) { expectSuccess = false })
          .upload("card one.rc", byteArrayOf(1, 2, 3))

      assertThat(upload)
        .isEqualTo(
          DocsApi.Upload.Published("https://preview.coo.ee/d/AbC123", "1 hour", "Remote Compose")
        )
      assertThat(seen!!.method).isEqualTo(HttpMethod.Post)
      assertThat(seen!!.url.toString()).isEqualTo("https://preview.coo.ee/docs?name=card+one.rc")
      // A public server takes it anonymously; no grant is ever sent to the ingest lane.
      assertThat(seen!!.headers[UiBuilderApi.TOKEN_HEADER]).isNull()
      assertThat(body).isEqualTo(byteArrayOf(1, 2, 3))
      // And the permalink opens straight back into the app.
      assertThat(PreviewLink.parse((upload as DocsApi.Upload.Published).permalink))
        .isEqualTo(PreviewLink.RemoteDocument("https://preview.coo.ee/d/AbC123/raw"))
    }

  @Test
  fun privateServerWithoutOperatorTokenSaysSo() =
    runBlocking<Unit> {
      val engine = MockEngine { respond("not found", HttpStatusCode.NotFound) }
      val upload =
        DocsApi(server, operatorToken = null, HttpClient(engine) { expectSuccess = false })
          .upload("card.rc", byteArrayOf(1))
      assertThat((upload as DocsApi.Upload.Refused).message).contains("operator token")
    }
}
