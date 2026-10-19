package ee.schimke.composeai.clients

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The client side of the `serve` streamed-frame wire protocol — the mirror image of
 * `cli/.../serve/ServeStreamProtocol.kt` (PR #1989). This is the single source of truth for what
 * the apps put on, and take off, the WebSocket:
 *
 * - **Decode** server→client text frames into [ServerMessage] ([parseServer]) — `frame` (base64 PNG
 *     + size + monotonic `seq`) and `error` (non-fatal; the lane stays open).
 * - **Encode** client→server messages ([setOverrides], [requestFrame], [input]) — the first two
 *   match the spike verbatim; [input] uses the top-level fields that
 *   `ServeStreamProtocol.parseClient` reads.
 *
 * Pure JSON, no IO — so it's unit-tested directly and the transport stays a thin pipe. Parsing
 * never throws: malformed or unknown server messages become [ServerMessage.Unknown] rather than
 * killing the session.
 */
object StreamMessages {

  /** A decoded server→client message. */
  sealed interface ServerMessage {
    data class Frame(val frame: StreamFrame) : ServerMessage

    data class Error(val message: String) : ServerMessage

    /** Anything else (a future server message type, or malformed text). [reason] aids debugging. */
    data class Unknown(val reason: String) : ServerMessage
  }

  private val json = Json { ignoreUnknownKeys = true }

  /** Parse a server text frame. Never throws. */
  fun parseServer(text: String): ServerMessage {
    return try {
      val obj = json.parseToJsonElement(text).jsonObject
      when (val type = (obj["type"] as? JsonPrimitive)?.contentOrNull) {
        "frame" -> parseFrame(obj)
        "error" -> ServerMessage.Error((obj["message"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        else -> ServerMessage.Unknown("unknown message type: $type")
      }
    } catch (e: Exception) {
      ServerMessage.Unknown("malformed message: ${e.message}")
    }
  }

  private fun parseFrame(obj: JsonObject): ServerMessage {
    val data =
      (obj["dataBase64"] as? JsonPrimitive)?.contentOrNull
        ?: return ServerMessage.Unknown("frame missing dataBase64")
    val bytes =
      try {
        Base64.getDecoder().decode(data)
      } catch (e: Exception) {
        return ServerMessage.Unknown("frame dataBase64 not valid base64: ${e.message}")
      }
    return ServerMessage.Frame(
      StreamFrame(
        seq = (obj["seq"] as? JsonPrimitive)?.longOrNull ?: 0L,
        codec = (obj["codec"] as? JsonPrimitive)?.contentOrNull ?: "png",
        widthPx = (obj["widthPx"] as? JsonPrimitive)?.intOrNull ?: 0,
        heightPx = (obj["heightPx"] as? JsonPrimitive)?.intOrNull ?: 0,
        bytes = bytes,
      )
    )
  }

  /** `{"type":"setOverrides","overrides":{…}}` — replace the display overrides and re-render. */
  fun setOverrides(overrides: Map<String, String>): String =
    buildJsonObject {
        put("type", "setOverrides")
        put("overrides", buildJsonObject { for ((k, v) in overrides) put(k, v) })
      }
      .toString()

  /** `{"type":"requestFrame"}` — re-render and push a frame at the current overrides. */
  fun requestFrame(): String = buildJsonObject { put("type", "requestFrame") }.toString()

  /**
   * `{"type":"input","kind":…}` — forward one pointer/key event. The serve protocol reads the event
   * fields at the top level, as specified in `docs/serve/SESSION-VIEWER-PROTOCOL.md` in
   * compose-ai-tools. The per-connection serve lane supplies the daemon's `frameStreamId`.
   */
  fun input(event: InputEvent): String =
    buildJsonObject {
        put("type", "input")
        put("kind", event.kind.wire)
        event.pixelX?.let { put("pixelX", it) }
        event.pixelY?.let { put("pixelY", it) }
        put("pointerId", event.pointerId)
        event.scrollDeltaY?.let { put("scrollDeltaY", it) }
        event.keyCode?.let { put("keyCode", it) }
      }
      .toString()
}
