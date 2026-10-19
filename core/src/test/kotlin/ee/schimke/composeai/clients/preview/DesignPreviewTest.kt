package ee.schimke.composeai.clients.preview

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.clients.SessionTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class DesignPreviewTest {

  private val server = ServerAddress("ui.example", 443, true)
  private val link = PreviewLink.Design(server, "d1", token = "tok")
  private val phone = DeviceProfile(1080, 2400, 2.625f, fontScale = 1.15f, dark = true)

  private val designJson =
    """{"schema":"compose-ui-builder-document/v1-candidate","id":"d1","revision":7,
       "catalogPin":{"systemId":"remote-m3"},
       "environment":{"widthDp":412,"heightDp":720,"density":1,"theme":"light",
                      "fixedTime":"2024-05-16T12:00:00Z"},"nodes":[]}"""

  private val requests = mutableListOf<HttpRequestData>()
  private val bodies = mutableListOf<String>()

  private fun preview(
    updates: (() -> kotlinx.coroutines.flow.Flow<String>)? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ): DesignPreview {
    val engine = MockEngine { request ->
      requests += request
      bodies += String(request.body.toByteArray())
      handler(request)
    }
    val api = UiBuilderApi(server, "tok", HttpClient(engine) { expectSuccess = false })
    return DesignPreview(
      DesignPreview.Source.Hosted(link),
      api,
      updates,
      1.milliseconds,
      1.milliseconds,
    )
  }

  private val MockRequestHandleScope.json
    get() = headersOf(HttpHeaders.ContentType, "application/json")

  @Test
  fun compilesTheDesignForThisDevice() =
    runBlocking<Unit> {
      val p = preview { request ->
        when (request.url.encodedPath) {
          "/api/ui-builder/v1/designs/d1" -> respond(designJson, headers = json)
          "/api/ui-builder/v1/documents/export.rc" -> respond(byteArrayOf(1, 2, 3))
          else -> error("unexpected ${request.url}")
        }
      }
      val content = p.load(phone)

      assertThat(content).isInstanceOf(PreviewContent.Document::class.java)
      content as PreviewContent.Document
      assertThat(content.bytes).isEqualTo(byteArrayOf(1, 2, 3))
      assertThat(content.fitted).isTrue()
      assertThat(content.revision).isEqualTo(7)

      // The token rides the header, and the compiled document carries the device's environment.
      assertThat(requests.all { it.headers[UiBuilderApi.TOKEN_HEADER] == "tok" }).isTrue()
      assertThat(requests[1].method).isEqualTo(HttpMethod.Post)
      val env = Json.parseToJsonElement(bodies[1]).jsonObject["environment"]!!.jsonObject
      assertThat(env["widthDp"]!!.jsonPrimitive.int).isEqualTo(411)
      assertThat(env["heightDp"]!!.jsonPrimitive.int).isEqualTo(914)
      assertThat(env["density"]!!.jsonPrimitive.double).isWithin(1e-6).of(2.625)
      assertThat(env["fontScale"]!!.jsonPrimitive.double).isWithin(1e-6).of(1.15)
      assertThat(env["theme"]!!.jsonPrimitive.content).isEqualTo("dark")
      // Fields the device has no opinion on are kept.
      assertThat(env["fixedTime"]!!.jsonPrimitive.content).isEqualTo("2024-05-16T12:00:00Z")
    }

  @Test
  fun inexpressibleDesignStreamsTheNativeRender() =
    runBlocking<Unit> {
      val p = preview { request ->
        when (request.url.encodedPath) {
          "/api/ui-builder/v1/designs/d1" -> respond(designJson, headers = json)
          "/api/ui-builder/v1/documents/export.rc" ->
            respond(
              "Material 3 Scaffold has no Remote lowering",
              HttpStatusCode.UnprocessableEntity,
            )
          "/api/ui-builder/v1/designs/d1/native-preview" ->
            respond(
              """{"schema":"compose-preview/ui-builder-native-preview/v1","designId":"d1",
               "revision":7,"previewId":"p","live":{"sessionId":"s9","previewId":"Design_d1"},
               "warnings":["TYPEFACE_SYSTEM_FONT_LOOKUP"],"nodeBounds":{}}""",
              headers = json,
            )
          else -> error("unexpected ${request.url}")
        }
      }
      val content = p.load(phone) as PreviewContent.Stream
      assertThat(content.link.target).isEqualTo(SessionTarget.Preview("Design_d1", "s9"))
      assertThat(content.link.webSocketUrl())
        .isEqualTo("wss://ui.example:443/s9/ws/Design_d1?token=tok")
      assertThat(content.warnings).containsExactly("TYPEFACE_SYSTEM_FONT_LOOKUP")
    }

  @Test
  fun nativeStillWhenThereIsNoLiveLane() =
    runBlocking<Unit> {
      val png = byteArrayOf(-119, 80, 78, 71)
      val p = preview { request ->
        when (request.url.encodedPath) {
          "/api/ui-builder/v1/designs/d1" -> respond(designJson, headers = json)
          // A host with no Remote Compose lane at all, like preview.coo.ee today.
          "/api/ui-builder/v1/documents/export.rc",
          "/api/ui-builder/v1/designs/d1/export.rc" -> respond("", HttpStatusCode.NotFound)
          "/api/ui-builder/v1/designs/d1/native-preview" ->
            respond(
              """{"designId":"d1","revision":7,"imageBase64":"${Base64.getEncoder().encodeToString(png)}"}""",
              headers = json,
            )
          else -> error("unexpected ${request.url}")
        }
      }
      val content = p.load(phone) as PreviewContent.Still
      assertThat(content.png).isEqualTo(png)
    }

  @Test
  fun olderHostFallsBackToTheAuthoredExport() =
    runBlocking<Unit> {
      val p = preview { request ->
        when (request.url.encodedPath) {
          "/api/ui-builder/v1/designs/d1" -> respond("", HttpStatusCode.MethodNotAllowed)
          "/api/ui-builder/v1/designs/d1/export.rc" -> respond(byteArrayOf(4))
          else -> error("unexpected ${request.url}")
        }
      }
      val content = p.load(phone) as PreviewContent.Document
      assertThat(content.fitted).isFalse()
    }

  @Test
  fun hostThatCannotCompileDocumentsStillServesTheAuthoredExport() =
    runBlocking<Unit> {
      val p = preview { request ->
        when (request.url.encodedPath) {
          "/api/ui-builder/v1/designs/d1" -> respond(designJson, headers = json)
          "/api/ui-builder/v1/documents/export.rc" -> respond("", HttpStatusCode.NotFound)
          "/api/ui-builder/v1/designs/d1/export.rc" -> respond(byteArrayOf(5))
          else -> error("unexpected ${request.url}")
        }
      }
      val content = p.load(phone) as PreviewContent.Document
      assertThat(content.fitted).isFalse()
      assertThat(content.revision).isEqualTo(7)
    }

  @Test
  fun inexpressibleDesignSkipsTheAuthoredExport() =
    runBlocking<Unit> {
      val p = preview { request ->
        when (request.url.encodedPath) {
          "/api/ui-builder/v1/designs/d1" -> respond(designJson, headers = json)
          "/api/ui-builder/v1/documents/export.rc" ->
            respond("no lowering", HttpStatusCode.UnprocessableEntity)
          "/api/ui-builder/v1/designs/d1/native-preview" ->
            respond("""{"designId":"d1","revision":7,"compileError":"nope"}""", headers = json)
          else -> error("unexpected ${request.url}")
        }
      }
      assertThat(p.load(phone)).isInstanceOf(PreviewContent.Failed::class.java)
      assertThat(requests.map { it.url.encodedPath })
        .doesNotContain("/api/ui-builder/v1/designs/d1/export.rc")
    }

  @Test
  fun missingDesignSaysSoInsteadOfBlamingAccess() =
    runBlocking<Unit> {
      val p = preview { request ->
        when (request.url.encodedPath) {
          "/api/ui-builder/v1/designs/d1" ->
            respond("design d1 was not found", HttpStatusCode.NotFound)
          else -> respond("authentication is required", HttpStatusCode.Unauthorized)
        }
      }
      val content = p.load(phone) as PreviewContent.Failed
      assertThat(content.message).contains("no design “d1” on ui.example")
      assertThat(requests).hasSize(1)
    }

  @Test
  fun localFileWhosePngNeedsAuthAsksToSignIn() =
    runBlocking<Unit> {
      val engine = MockEngine { request ->
        when (request.url.encodedPath) {
          "/api/ui-builder/v1/documents/export.rc" -> respond("", HttpStatusCode.NotFound)
          else -> respond("authentication is required", HttpStatusCode.Unauthorized)
        }
      }
      val api = UiBuilderApi(server, null, HttpClient(engine) { expectSuccess = false })
      val document = Json.parseToJsonElement(designJson).jsonObject
      val p =
        DesignPreview(
          DesignPreview.Source.Local(server, null, document),
          api,
          null,
          1.milliseconds,
          1.milliseconds,
        )
      assertThat(p.load(phone)).isEqualTo(PreviewContent.NeedsSignIn(server, 401, signedIn = false))
    }

  @Test
  fun refusedAccessSaysSo() =
    runBlocking<Unit> {
      val p = preview { respond("authentication is required", HttpStatusCode.Unauthorized) }
      val content = p.load(phone) as PreviewContent.NeedsSignIn
      assertThat(content).isEqualTo(PreviewContent.NeedsSignIn(server, 401, signedIn = true))
    }

  @Test
  fun pushedEditReloadsAndFailedReloadKeepsTheLastGoodDocument() =
    runBlocking<Unit> {
      var compiles = 0
      val updates = Channel<String>(Channel.UNLIMITED)
      val p =
        preview(updates = { updates.receiveAsFlow() }) { request ->
          when (request.url.encodedPath) {
            "/api/ui-builder/v1/designs/d1" ->
              respond(
                designJson.replace("\"revision\":7", "\"revision\":${7 + compiles}"),
                headers = json,
              )
            "/api/ui-builder/v1/documents/export.rc" -> {
              compiles++
              if (compiles == 3) respond("boom", HttpStatusCode.InternalServerError)
              else respond(byteArrayOf(compiles.toByte()))
            }
            "/api/ui-builder/v1/designs/d1/native-preview" ->
              respond("no lane", HttpStatusCode.ServiceUnavailable)
            else -> error("unexpected ${request.url}")
          }
        }
      val job = launch { p.run(MutableStateFlow(phone)) }
      withTimeout(5_000) {
        p.state.first {
          (it.content as? PreviewContent.Document)?.bytes?.contentEquals(byteArrayOf(1)) == true
        }

        // Presence and stale revisions don't reload.
        updates.send("""{"type":"presence","revision":99}""")
        updates.send("""{"type":"delta","revision":7}""")
        // A new revision does.
        updates.send("""{"type":"delta","delta":{"revision":8}}""")
        p.state.first {
          (it.content as? PreviewContent.Document)?.bytes?.contentEquals(byteArrayOf(2)) == true
        }

        updates.send("""{"type":"delta","revision":9}""")
        val failed = p.state.first { it.notice != null }
        assertThat((failed.content as PreviewContent.Document).bytes).isEqualTo(byteArrayOf(2))
      }
      assertThat(compiles).isEqualTo(3)
      job.cancel()
    }

  @Test
  fun isEditIgnoresPresenceAndKnownRevisions() {
    assertThat(DesignPreview.isEdit("""{"type":"presence"}""", 3)).isFalse()
    assertThat(DesignPreview.isEdit("""{"update":{"kind":"operationOutcome"}}""", 3)).isFalse()
    assertThat(DesignPreview.isEdit("""{"type":"delta","revision":3}""", 3)).isFalse()
    assertThat(DesignPreview.isEdit("""{"type":"delta","revision":4}""", 3)).isTrue()
    assertThat(DesignPreview.isEdit("""{"type":"snapshot"}""", 3)).isTrue()
    assertThat(DesignPreview.isEdit("not json", 3)).isTrue()
  }

  @Test
  fun forDeviceOnlyTouchesTheEnvironment() {
    val doc = Json.parseToJsonElement(designJson).jsonObject
    val fitted: JsonObject = with(DesignPreview) { doc.forDevice(phone) }
    assertThat(fitted.keys).containsExactlyElementsIn(doc.keys)
    assertThat(fitted["nodes"]).isEqualTo(doc["nodes"])
  }
}
