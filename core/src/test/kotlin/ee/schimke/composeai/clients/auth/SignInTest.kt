package ee.schimke.composeai.clients.auth

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.clients.preview.PreviewLink
import ee.schimke.composeai.clients.preview.ServerAddress
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class SignInTest {

  private val server = ServerAddress("ui.example", 443, true)
  private val json = headersOf(HttpHeaders.ContentType, "application/json")
  private val requests = mutableListOf<Pair<HttpRequestData, String>>()

  private fun signIn(store: GrantStore, polls: List<String>, open: String? = OPEN): SignIn {
    val queue = ArrayDeque(polls)
    val engine = MockEngine { request ->
      requests += request to String(request.body.toByteArray())
      when (request.url.encodedPath) {
        "/agent-access/request" ->
          if (open == null) respond("Not Found", HttpStatusCode.NotFound)
          else respond(open, headers = json)
        "/agent-access/poll" -> respond(queue.removeFirst(), headers = json)
        "/agent-access/revoke" -> respond("""{"revoked":true}""", headers = json)
        else -> error("unexpected ${request.url}")
      }
    }
    val api = AccessGrantApi(server, HttpClient(engine) { expectSuccess = false })
    return SignIn(server, store, api, clock = { 1_000_000L }, pollWait = kotlin.time.Duration.ZERO)
  }

  @Test
  fun approvalStoresTheGrant() =
    runBlocking<Unit> {
      val store = InMemoryGrantStore()
      val states =
        signIn(
            store,
            listOf(
              """{"status":"pending","retryAfterSeconds":0}""",
              """{"status":"approved","token":"cpg_abc","scopes":["preview","live"],
               "capabilities":["ui-builder-read","ui-builder-export"],"expiresInSeconds":3600,
               "approvedBy":"yschimke"}""",
            ),
          )
          .run("Compose Device Preview on Pixel")
          .toList()

      assertThat(states[0]).isEqualTo(SignInState.Requesting)
      // The approval page is built on the server that was asked, not taken from the reply.
      assertThat(states[1])
        .isEqualTo(
          SignInState.AwaitingApproval("https://ui.example/agent-access/r%201", "WXYZ-1234")
        )
      val grant = (states.last() as SignInState.SignedIn).grant
      assertThat(grant.token).isEqualTo("cpg_abc")
      assertThat(grant.approvedBy).isEqualTo("yschimke")
      assertThat(grant.expiresAtMillis).isEqualTo(1_000_000L + 3_600_000L)
      assertThat(store.valid(server, nowMillis = 1_000_001L)).isEqualTo(grant)
      assertThat(store.valid(server, nowMillis = 1_000_000L + 3_600_000L)).isNull()

      // It asks for exactly what a preview needs: live streaming, read and export — never write.
      val body = Json.parseToJsonElement(requests.first().second).jsonObject
      assertThat(body["scope"]!!.jsonPrimitive.content).isEqualTo("live")
      assertThat(body["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
        .containsExactly("ui-builder-read", "ui-builder-export")
      assertThat(body["label"]!!.jsonPrimitive.content).isEqualTo("Compose Device Preview on Pixel")
      // The device secret only ever travels in the poll body.
      assertThat(requests[1].second).contains("\"deviceSecret\":\"s3cret\"")
    }

  @Test
  fun deniedSaysSoAndStoresNothing() =
    runBlocking<Unit> {
      val store = InMemoryGrantStore()
      val last =
        signIn(store, listOf("""{"status":"denied","message":"declined by yschimke"}"""))
          .run("x")
          .toList()
          .last()
      assertThat(last).isEqualTo(SignInState.Failed("declined by yschimke"))
      assertThat(store.get(server.httpBase)).isNull()
    }

  @Test
  fun serverWithoutGrantsSaysSo() =
    runBlocking<Unit> {
      val last = signIn(InMemoryGrantStore(), emptyList(), open = null).run("x").toList().last()
      assertThat((last as SignInState.Failed).message).contains("doesn't offer sign-in")
    }

  @Test
  fun unreachableServerFailsInsteadOfThrowing() =
    runBlocking<Unit> {
      val engine = MockEngine { throw java.io.IOException("connection refused") }
      val api = AccessGrantApi(server, HttpClient(engine) { expectSuccess = false })
      val last = SignIn(server, InMemoryGrantStore(), api).run("x").toList().last()
      assertThat((last as SignInState.Failed).message).contains("connection refused")
    }

  @Test
  fun droppedPollIsRetried() =
    runBlocking<Unit> {
      var polls = 0
      val open = OPEN.replace("\"pollIntervalSeconds\":5", "\"pollIntervalSeconds\":0")
      val engine = MockEngine { request ->
        when (request.url.encodedPath) {
          "/agent-access/request" -> respond(open, headers = json)
          else ->
            if (polls++ == 0) throw java.io.IOException("reset")
            else respond("""{"status":"approved","token":"cpg_x"}""", headers = json)
        }
      }
      val api = AccessGrantApi(server, HttpClient(engine) { expectSuccess = false })
      val store = InMemoryGrantStore()
      val last =
        SignIn(server, store, api, clock = { 0L }, pollWait = kotlin.time.Duration.ZERO)
          .run("x")
          .toList()
          .last()
      assertThat(last).isInstanceOf(SignInState.SignedIn::class.java)
      assertThat(store.get(server.httpBase)?.token).isEqualTo("cpg_x")
    }

  @Test
  fun signOutRevokesAndForgets() =
    runBlocking<Unit> {
      val store = InMemoryGrantStore()
      store.put(AccessGrant(server.httpBase, "cpg_abc"))
      signIn(store, emptyList()).signOut()
      assertThat(store.get(server.httpBase)).isNull()
      val (revoke, _) = requests.single()
      assertThat(revoke.url.encodedPath).isEqualTo("/agent-access/revoke")
      assertThat(revoke.headers["X-Compose-Preview-Token"]).isEqualTo("cpg_abc")
    }

  @Test
  fun linksTakeAGrant() {
    val design = PreviewLink.Design(server, "d1")
    assertThat(design.authServer).isEqualTo(server)
    assertThat(design.withToken("t").authToken).isEqualTo("t")
    val rc = PreviewLink.RemoteDocument("https://ui.example/m3/render/a.rc")
    assertThat(rc.authServer).isEqualTo(server)
    assertThat(PreviewLink.LocalDocument("content://x").authServer).isNull()
  }

  /**
   * Replies captured from preview.coo.ee (device secret redacted), so drift in the real shape fails
   * here.
   */
  @Test
  fun decodesTheLiveServersReplies() {
    val open =
      AccessGrantApi.json.decodeFromString(
        AccessGrantApi.OpenResponse.serializer(),
        """{"requestId":"Sx999qdOOG-tFp16ybrQjA","deviceSecret":"<redacted>","userCode":"AJRW-NR9M",
          "approveUrl":"https://preview.coo.ee/agent-access/Sx999qdOOG-tFp16ybrQjA",
          "pollUrl":"https://preview.coo.ee/agent-access/poll","expiresInSeconds":1799,
          "pollIntervalSeconds":3,"requestedScope":"live","requestedTtlSeconds":28800,
          "maxScope":"live","maxTtlSeconds":28800,
          "requestedCapabilities":["ui-builder-read","ui-builder-export"],
          "maxCapabilities":["images","ui-builder-read","ui-builder-write","ui-builder-export",
          "ui-builder-destructive"]}""",
      )
    assertThat(open.userCode).isEqualTo("AJRW-NR9M")
    assertThat(open.requestedCapabilities).containsExactly("ui-builder-read", "ui-builder-export")
    // The page the app opens is the one the live server would have named.
    assertThat(
        AccessGrantApi.approvalUrl(ServerAddress("preview.coo.ee", 443, true), open.requestId)
      )
      .isEqualTo(open.approveUrl)

    val pending =
      AccessGrantApi.json.decodeFromString(
        AccessGrantApi.PollResponse.serializer(),
        """{"status":"pending","token":null,"tokenHeader":null,"scopes":[],"capabilities":[],
          "expiresInSeconds":1791,"approvedBy":null,"retryAfterSeconds":3,
          "message":"waiting for a human to approve this request"}""",
      )
    assertThat(pending.status).isEqualTo("pending")
    assertThat(pending.retryAfterSeconds).isEqualTo(3)
  }

  private companion object {
    const val OPEN =
      """{"requestId":"r 1","deviceSecret":"s3cret","userCode":"WXYZ-1234",
          "approveUrl":"https://elsewhere.example/agent-access/r%201","pollUrl":"https://ui.example/agent-access/poll",
          "expiresInSeconds":1800,"pollIntervalSeconds":5,"requestedScope":"live","maxScope":"live"}"""
  }
}
