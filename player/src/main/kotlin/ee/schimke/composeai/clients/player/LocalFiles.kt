package ee.schimke.composeai.clients.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ee.schimke.composeai.clients.preview.PreviewLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reading `.rc` and `.uid` files handed to the app (`content://` from a file picker or share). */
object LocalFiles {
  /** Documents above this are not previews; refusing them keeps a bad pick from exhausting memory. */
  const val MAX_BYTES: Int = 16 * 1024 * 1024

  /** The app's bundled sample documents (`file:///android_asset/…`). */
  const val ASSET_PREFIX: String = "file:///android_asset/"

  /** Sample `.rc` documents bundled with the app, to check the player with no server at all. */
  val SAMPLES: List<String> = listOf("samples/weather.rc", "samples/entities.rc").map { ASSET_PREFIX + it }

  suspend fun read(context: Context, uri: String): ByteArray =
    withContext(Dispatchers.IO) {
      val stream =
        if (uri.startsWith(ASSET_PREFIX)) context.assets.open(uri.removePrefix(ASSET_PREFIX))
        else context.contentResolver.openInputStream(Uri.parse(uri)) ?: error("can't open $uri")
      stream.use {
        val bytes = it.readNBytesCompat(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "file is larger than ${MAX_BYTES / (1024 * 1024)} MB" }
        bytes
      }
    }

  /** The file's display name (`card.rc`), falling back to the URI's last segment. */
  fun displayName(context: Context, uri: String): String {
    val parsed = Uri.parse(uri)
    val fromProvider =
      runCatching {
          context.contentResolver
            .query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }
        .getOrNull()
    return fromProvider ?: parsed.lastPathSegment ?: uri
  }

  /** Whether a picked file is a `.uid` design (else it's played as a `.rc` document). */
  fun isDesign(context: Context, uri: String): Boolean =
    displayName(context, uri).endsWith(PreviewLink.UID_EXTENSION, ignoreCase = true)

  private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
      val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
      if (read < 0) break
      out.write(buffer, 0, read)
    }
    return out.toByteArray()
  }
}
