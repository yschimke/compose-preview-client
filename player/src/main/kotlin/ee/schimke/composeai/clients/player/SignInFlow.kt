package ee.schimke.composeai.clients.player

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import ee.schimke.composeai.clients.auth.AccessGrant
import ee.schimke.composeai.clients.auth.GrantStore
import ee.schimke.composeai.clients.auth.SignIn
import ee.schimke.composeai.clients.auth.SignInState
import ee.schimke.composeai.clients.preview.ServerAddress
import kotlinx.serialization.json.Json

/**
 * Each server's grant, in the app's private preferences. Grants are short-lived (hours) and die
 * with a server restart, so they are kept like a session cookie: private to the app, never backed
 * up (the apps opt out of backup), and dropped on sign-out or when the server stops accepting them.
 */
class SharedPrefsGrantStore(context: Context) : GrantStore {
  private val prefs = context.getSharedPreferences("access-grants", Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  override fun get(origin: String): AccessGrant? =
    prefs.getString(origin, null)?.let {
      runCatching { json.decodeFromString(AccessGrant.serializer(), it) }.getOrNull()
    }

  override fun put(grant: AccessGrant) {
    prefs.edit().putString(grant.origin, json.encodeToString(AccessGrant.serializer(), grant)).apply()
  }

  override fun remove(origin: String) {
    prefs.edit().remove(origin).apply()
  }
}

/** What the person sees on the server's approval page, so they know which device they're letting in. */
fun signInLabel(): String = "Compose Device Preview on ${Build.MANUFACTURER} ${Build.MODEL}".trim()

/**
 * Runs a sign-in to [server] while composed, and hands [content] its state plus a retry. Calls
 * [onSignedIn] once with the grant. Leaving composition abandons the request (it expires on the
 * server unapproved).
 */
@Composable
fun SignInFlow(
  server: ServerAddress,
  grants: GrantStore,
  onSignedIn: (AccessGrant) -> Unit,
  content: @Composable (state: SignInState, retry: () -> Unit) -> Unit,
) {
  var attempt by remember(server) { mutableIntStateOf(0) }
  var state by remember(server) { mutableStateOf<SignInState>(SignInState.Requesting) }
  val currentOnSignedIn by rememberUpdatedState(onSignedIn)
  LaunchedEffect(server, attempt) {
    SignIn(server, grants).run(signInLabel()).collect {
      state = it
      if (it is SignInState.SignedIn) currentOnSignedIn(it.grant)
    }
  }
  content(state) { attempt++ }
}
