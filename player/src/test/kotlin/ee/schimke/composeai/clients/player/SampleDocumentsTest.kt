package ee.schimke.composeai.clients.player

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The bundled samples are real Remote Compose documents this player version reads — the offline
 * check that playback works at all, before any server is involved.
 */
class SampleDocumentsTest {

  @Test
  fun everySampleDecodes() {
    for (sample in LocalFiles.SAMPLES) {
      val file = File("src/main/assets", sample.removePrefix(LocalFiles.ASSET_PREFIX))
      val document = decode(file.readBytes())
      // Wrap-content documents (no fixed canvas), so check for a laid-out tree rather than a size.
      assertThat(document.operations).isNotEmpty()
      assertThat(document.rootLayoutComponent).isNotNull()
    }
  }
}
