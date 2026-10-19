package ee.schimke.composeai.clients.mobile

import android.content.Context
import ee.schimke.composeai.clients.preview.ServerAddress
import java.net.URI

/**
 * The UI builder a local `.uid` file is compiled on. A hosted design's link names its own server;
 * a file doesn't, so the app remembers one. Defaults to the public hosted builder.
 */
class UiBuilderSettings(context: Context) {
  private val prefs = context.getSharedPreferences("ui-builder", Context.MODE_PRIVATE)

  var serverUrl: String
    get() = prefs.getString(KEY_SERVER, null) ?: DEFAULT_SERVER
    set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

  var token: String?
    get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    set(value) = prefs.edit().putString(KEY_TOKEN, value?.trim()).apply()

  val server: ServerAddress?
    get() = parseServer(serverUrl)

  companion object {
    const val DEFAULT_SERVER: String = "https://preview.coo.ee"
    private const val KEY_SERVER = "server"
    private const val KEY_TOKEN = "token"

    fun parseServer(url: String): ServerAddress? =
      runCatching { URI(url.trim()) }
        .getOrNull()
        ?.takeIf { it.scheme == "http" || it.scheme == "https" }
        ?.let(ServerAddress::of)
  }
}
