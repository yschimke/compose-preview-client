package ee.schimke.composeai.clients.auth

import ee.schimke.composeai.clients.preview.ServerAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Where a sign-in is, for the UI to draw. */
sealed interface SignInState {
  data object Requesting : SignInState

  /**
   * Waiting for a person to approve: open [approvalUrl] (on this device, or on a phone or laptop
   * signed in to the server) and check the page shows [userCode].
   */
  data class AwaitingApproval(val approvalUrl: String, val userCode: String) : SignInState

  data class SignedIn(val grant: AccessGrant) : SignInState

  /** It didn't work; [message] says why, and whether asking again could help. */
  data class Failed(val message: String) : SignInState
}

/** Keeps each server's grant. */
interface GrantStore {
  fun get(origin: String): AccessGrant?

  fun put(grant: AccessGrant)

  fun remove(origin: String)

  /** A grant for [server] that hasn't expired, or null. */
  fun valid(server: ServerAddress, nowMillis: Long = System.currentTimeMillis()): AccessGrant? =
    get(server.httpBase)?.takeUnless { it.isExpired(nowMillis) }
}

class InMemoryGrantStore : GrantStore {
  private val grants = java.util.concurrent.ConcurrentHashMap<String, AccessGrant>()

  override fun get(origin: String): AccessGrant? = grants[origin]

  override fun put(grant: AccessGrant) {
    grants[grant.origin] = grant
  }

  override fun remove(origin: String) {
    grants.remove(origin)
  }
}

/**
 * Signs this app in to one server with a device grant: request, show the approval link and code,
 * poll until a person decides, and keep the token. The flow completes after [SignInState.SignedIn]
 * or [SignInState.Failed]; cancel its collector to abandon it.
 */
class SignIn(
  private val server: ServerAddress,
  private val store: GrantStore,
  private val api: AccessGrantApi = AccessGrantApi(server),
  private val clock: () -> Long = System::currentTimeMillis,
  /** Held-open poll length; under OkHttp's 10s default read timeout so a wait never times out. */
  private val pollWait: Duration = 8.seconds,
) {

  fun run(label: String): Flow<SignInState> = flow {
    emit(SignInState.Requesting)
    val opened =
      try {
        api.request(label)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        emit(
          SignInState.Failed("Couldn't reach ${server.label}: ${e.message ?: e::class.simpleName}")
        )
        return@flow
      }
    val request =
      when (opened) {
        is AccessGrantApi.Answer.Ok -> opened.value
        is AccessGrantApi.Answer.Refused -> {
          emit(SignInState.Failed(refusalMessage(opened)))
          return@flow
        }
      }
    emit(
      SignInState.AwaitingApproval(
        AccessGrantApi.approvalUrl(server, request.requestId),
        request.userCode,
      )
    )

    val deadline = clock() + request.expiresInSeconds * 1_000
    while (clock() < deadline) {
      val answer =
        try {
          api.poll(request.requestId, request.deviceSecret, pollWait.inWholeSeconds)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // A dropped connection or a timeout mid-wait: the request is still open on the server.
          AccessGrantApi.Answer.Refused(0, e.message.orEmpty())
        }
      val poll =
        when (answer) {
          is AccessGrantApi.Answer.Ok -> answer.value
          is AccessGrantApi.Answer.Refused -> {
            // A transient failure (a proxy hiccup, a redeploy): back off and keep waiting.
            delay(request.pollIntervalSeconds.coerceAtLeast(1).seconds)
            continue
          }
        }
      when (poll.status) {
        "approved" -> {
          val token = poll.token
          if (token.isNullOrBlank()) {
            emit(SignInState.Failed("${server.label} approved the request but sent no token"))
            return@flow
          }
          val grant =
            AccessGrant(
              origin = server.httpBase,
              token = token,
              scopes = poll.scopes,
              capabilities = poll.capabilities,
              expiresAtMillis = poll.expiresInSeconds?.let { clock() + it * 1_000 },
              approvedBy = poll.approvedBy,
            )
          store.put(grant)
          emit(SignInState.SignedIn(grant))
          return@flow
        }
        "pending" -> {
          // A held poll already waited; an immediate one is told how long to back off.
          val wait =
            poll.retryAfterSeconds
              ?: if (pollWait > Duration.ZERO) 0 else request.pollIntervalSeconds
          if (wait > 0) delay(wait.seconds)
        }
        "denied" -> {
          emit(SignInState.Failed(poll.message ?: "The request was declined on ${server.label}."))
          return@flow
        }
        else -> {
          emit(
            SignInState.Failed(poll.message ?: "The request ${poll.status}. Try signing in again.")
          )
          return@flow
        }
      }
    }
    emit(SignInState.Failed("Nobody approved the request in time. Try signing in again."))
  }

  /** Give the grant back and forget it. Forgets it even when the server can't be reached. */
  suspend fun signOut() {
    val grant = store.get(server.httpBase) ?: return
    store.remove(server.httpBase)
    runCatching { api.revoke(grant.token) }
  }

  private fun refusalMessage(refused: AccessGrantApi.Answer.Refused): String =
    when (refused.status) {
      404 -> "${server.label} doesn't offer sign-in for apps (agent access grants are off)."
      else -> "${server.label} refused the sign-in request (HTTP ${refused.status})."
    }
}
