package ee.schimke.composeai.clients.wear

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.remote.interactions.RemoteActivityHelper
import ee.schimke.composeai.clients.auth.AccessGrant
import ee.schimke.composeai.clients.auth.GrantStore
import ee.schimke.composeai.clients.auth.SignInState
import ee.schimke.composeai.clients.player.SignInFlow
import ee.schimke.composeai.clients.player.SignInNeeded
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The watch's sign-in, over a preview the server refused. Approving needs a browser where the
 * person is signed in, so the approval page opens on the paired phone; the watch shows the code to
 * check and picks the grant up when they approve.
 */
@Composable
fun WearSignIn(
  needed: SignInNeeded,
  grants: GrantStore,
  onSignedIn: (AccessGrant) -> Unit,
  onBack: () -> Unit,
) {
  var signingIn by remember(needed) { mutableStateOf(false) }
  Column(
    Modifier.fillMaxSize()
      .background(Color.Black)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 14.dp, vertical = 28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (!signingIn) {
      Text(
        if (needed.hadCredential && needed.status == 401) "Sign-in expired"
        else if (needed.hadCredential) "Not shared with you"
        else "Sign in to ${needed.server.label}",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
      )
      Button(
        onClick = {
          if (needed.hadCredential) grants.remove(needed.server.httpBase)
          signingIn = true
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Sign in on phone")
      }
      Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    } else {
      val context = LocalContext.current
      SignInFlow(needed.server, grants, onSignedIn) { state, retry ->
        var sentToPhone by remember(state) { mutableStateOf<Boolean?>(null) }
        if (state is SignInState.AwaitingApproval) {
          LaunchedEffect(state) { sentToPhone = openOnPhone(context, state.approvalUrl) }
        }
        WearSignInContent(state, sentToPhone, retry, onCancel = { signingIn = false })
      }
    }
  }
}

@Composable
private fun WearSignInContent(
  state: SignInState,
  sentToPhone: Boolean?,
  retry: () -> Unit,
  onCancel: () -> Unit,
) {
  when (state) {
    SignInState.Requesting -> CircularProgressIndicator()
    is SignInState.AwaitingApproval -> {
      Text(
        if (sentToPhone == false) "Open on a signed-in device:" else "Approve on your phone. Code:",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
      )
      Text(
        state.userCode,
        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
      )
      if (sentToPhone == false) {
        Text(state.approvalUrl, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
      }
      Text("Waiting…", style = MaterialTheme.typography.labelSmall)
    }
    is SignInState.SignedIn -> Text("Signed in", style = MaterialTheme.typography.titleMedium)
    is SignInState.Failed -> {
      Text(state.message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
      Button(onClick = retry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
    }
  }
  Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

/** Opens [url] in the paired phone's browser; false when there's no phone to open it on. */
private suspend fun openOnPhone(context: android.content.Context, url: String): Boolean {
  val future =
    runCatching {
        RemoteActivityHelper(context)
          .startRemoteActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
          )
      }
      .getOrElse {
        return false
      }
  return suspendCancellableCoroutine { continuation ->
    future.addListener(
      { continuation.resume(runCatching { future.get() }.isSuccess) },
      Runnable::run,
    )
    continuation.invokeOnCancellation { future.cancel(false) }
  }
}

@Preview(
  name = "Wear sign-in — waiting",
  showBackground = true,
  backgroundColor = 0xFF000000,
  widthDp = 220,
  heightDp = 220,
)
@Composable
private fun WearSignInWaitingPreview() {
  MaterialTheme {
    Column(
      Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      WearSignInContent(
        SignInState.AwaitingApproval("https://preview.coo.ee/agent-access/abc", "WXYZ-1234"),
        sentToPhone = true,
        retry = {},
        onCancel = {},
      )
    }
  }
}
