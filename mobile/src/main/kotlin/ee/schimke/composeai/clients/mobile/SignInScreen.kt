package ee.schimke.composeai.clients.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.clients.auth.AccessGrant
import ee.schimke.composeai.clients.auth.GrantStore
import ee.schimke.composeai.clients.auth.SignInState
import ee.schimke.composeai.clients.player.SignInFlow
import ee.schimke.composeai.clients.player.SignInNeeded
import ee.schimke.composeai.clients.preview.ServerAddress

/**
 * Over a preview the server refused: explains why, and signs this device in on request. Signing
 * in opens the server's approval page in the browser — where the person is already signed in to
 * GitHub, or holds the operator token — and the app picks the grant up as soon as they approve.
 */
@Composable
fun SignInOverlay(
  needed: SignInNeeded,
  grants: GrantStore,
  onSignedIn: (AccessGrant) -> Unit,
  onBack: () -> Unit,
) {
  var signingIn by remember(needed) { mutableStateOf(false) }
  Box(
    Modifier.fillMaxSize().background(Color(0xF0101014)).safeDrawingPadding().padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.widthIn(max = 420.dp)) {
      Column(
        Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (signingIn) {
          SignInPanel(
            server = needed.server,
            grants = grants,
            onSignedIn = onSignedIn,
            onCancel = { signingIn = false },
          )
        } else {
          Text(
            when {
              !needed.hadCredential -> "Sign in to ${needed.server.label}"
              needed.status == 401 -> "Your sign-in has expired"
              else -> "This isn't shared with you"
            },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
          )
          Text(
            when {
              !needed.hadCredential ->
                "${needed.server.label} only shows this to people it knows. Sign this device " +
                  "in: you'll approve it in your browser."
              needed.status == 401 -> "Sign this device in again to keep previewing."
              else ->
                "The account that approved this device can't open it. Ask the owner to share " +
                  "it, or sign in with an account that can."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
          Button(
            onClick = {
              // A grant the server no longer accepts is only in the way of the new one.
              if (needed.hadCredential) grants.remove(needed.server.httpBase)
              signingIn = true
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(if (needed.hadCredential) "Sign in again" else "Sign in")
          }
          TextButton(onClick = onBack) { Text("Back") }
        }
      }
    }
  }
}

/**
 * The sign-in itself: asks [server] for access, opens the approval page, shows the code to match,
 * and waits for the approval.
 */
@Composable
fun SignInPanel(
  server: ServerAddress,
  grants: GrantStore,
  onSignedIn: (AccessGrant) -> Unit,
  onCancel: () -> Unit,
) {
  val context = LocalContext.current
  val openInBrowser: (String) -> Boolean = { url ->
    try {
      context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
      true
    } catch (_: ActivityNotFoundException) {
      false
    }
  }
  SignInFlow(server, grants, onSignedIn) { state, retry ->
    SignInContent(server, state, openInBrowser, retry, onCancel)
  }
}

@Composable
private fun SignInContent(
  server: ServerAddress,
  state: SignInState,
  openInBrowser: (String) -> Boolean,
  retry: () -> Unit,
  onCancel: () -> Unit,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      "Sign in to ${server.label}",
      style = MaterialTheme.typography.titleLarge,
      textAlign = TextAlign.Center,
    )
    when (state) {
      SignInState.Requesting -> CircularProgressIndicator()
      is SignInState.AwaitingApproval -> {
        var opened by remember(state) { mutableStateOf(false) }
        // One tap: the approval page opens as soon as there's a request to approve.
        LaunchedEffect(state) { opened = openInBrowser(state.approvalUrl) }
        Text(
          "Approve this device in your browser, and check the page shows this code:",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        Text(
          state.userCode,
          style =
            MaterialTheme.typography.headlineMedium.copy(
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.SemiBold,
              letterSpacing = 4.sp,
            ),
        )
        if (!opened) {
          Text(
            "No browser opened. On any device signed in to ${server.label}, open:\n" +
              state.approvalUrl,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
          )
        }
        OutlinedButton(onClick = { opened = openInBrowser(state.approvalUrl) }) {
          Text("Open the approval page")
        }
        Box(contentAlignment = Alignment.Center) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CircularProgressIndicator(Modifier.padding(4.dp))
            Text("Waiting for approval…", style = MaterialTheme.typography.labelLarge)
          }
        }
      }
      is SignInState.SignedIn ->
        Text(
          "Signed in" + (state.grant.approvedBy?.let { " — approved by $it" } ?: ""),
          style = MaterialTheme.typography.bodyLarge,
        )
      is SignInState.Failed -> {
        Text(
          state.message,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          textAlign = TextAlign.Center,
        )
        Button(onClick = retry) { Text("Try again") }
      }
    }
    TextButton(onClick = onCancel) { Text("Cancel") }
  }
}

@Preview(name = "Sign in — waiting for approval", widthDp = 360, heightDp = 720)
@Composable
private fun SignInWaitingPreview() {
  MaterialTheme {
    Surface(Modifier.fillMaxSize()) {
      Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        SignInContent(
          ServerAddress("preview.coo.ee", 443, true),
          SignInState.AwaitingApproval("https://preview.coo.ee/agent-access/abc", "WXYZ-1234"),
          openInBrowser = { true },
          retry = {},
          onCancel = {},
        )
      }
    }
  }
}

@Preview(name = "Sign in — needed", widthDp = 360, heightDp = 720)
@Composable
private fun SignInNeededPreview() {
  MaterialTheme {
    SignInOverlay(
      SignInNeeded(ServerAddress("preview.coo.ee", 443, true), 401, hadCredential = false),
      grants = ee.schimke.composeai.clients.auth.InMemoryGrantStore(),
      onSignedIn = {},
      onBack = {},
    )
  }
}
