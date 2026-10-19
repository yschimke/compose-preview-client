package ee.schimke.composeai.clients.preview

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.clients.SessionLink
import ee.schimke.composeai.clients.SessionTarget
import org.junit.Test

class PreviewLinkTest {

  @Test
  fun editorPermalinkIsADesign() {
    val link = PreviewLink.parse("https://ui.coo.ee/ui-builder/cheeky-raccoon")
    assertThat(link)
      .isEqualTo(PreviewLink.Design(ServerAddress("ui.coo.ee", 443, true), "cheeky-raccoon"))
    assertThat((link as PreviewLink.Design).editorUrl)
      .isEqualTo("https://ui.coo.ee/ui-builder/cheeky-raccoon")
  }

  @Test
  fun catalogScopedEditorLinkKeepsTokenAndRevision() {
    val link =
      PreviewLink.parse(
        "http://10.0.0.2:8723/ui-builder/m3-catalog/home%20screen?token=t&revision=4"
      )
    assertThat(link)
      .isEqualTo(PreviewLink.Design(ServerAddress("10.0.0.2", 8723, false), "home screen", "t", 4))
  }

  @Test
  fun anyDesignApiRouteIsTheDesignSoItKeepsFollowingEdits() {
    val link = PreviewLink.parse("https://h/api/ui-builder/v1/designs/d1/export.rc?token=t")
    assertThat(link).isEqualTo(PreviewLink.Design(ServerAddress("h", 443, true), "d1", "t"))
  }

  @Test
  fun customDesignScheme() {
    val link = PreviewLink.parse("composeai://design?host=h&id=d&token=t&secure=true")
    assertThat(link).isEqualTo(PreviewLink.Design(ServerAddress("h", 443, true), "d", "t"))
    assertThat(PreviewLink.parse("composeai://design?host=h&id=d"))
      .isEqualTo(PreviewLink.Design(ServerAddress("h", SessionLink.DEFAULT_PORT, false), "d"))
  }

  @Test
  fun servedRcIsARemoteDocument() {
    val link = PreviewLink.parse("https://preview.coo.ee/m3/render/Button.rc?token=t")
    assertThat(link)
      .isEqualTo(
        PreviewLink.RemoteDocument("https://preview.coo.ee/m3/render/Button.rc?token=t", "t")
      )
    assertThat(link!!.label).isEqualTo("Button.rc")
  }

  @Test
  fun customRcScheme() {
    assertThat(PreviewLink.parse("composeai://rc?src=https%3A%2F%2Fcdn%2Fcard.rc"))
      .isEqualTo(PreviewLink.RemoteDocument("https://cdn/card.rc"))
  }

  @Test
  fun localFilesAreLocalDocuments() {
    assertThat(PreviewLink.parse("content://downloads/42"))
      .isEqualTo(PreviewLink.LocalDocument("content://downloads/42"))
  }

  @Test
  fun sessionLinksStillStream() {
    val link = PreviewLink.parse("composeai://session?host=h&preview=p&token=t")
    assertThat(link).isInstanceOf(PreviewLink.Stream::class.java)
    assertThat(PreviewLink.parse("http://h:7341/p/Foo?token=t"))
      .isEqualTo(PreviewLink.Stream(SessionLink("h", 7341, "t", SessionTarget.Preview("Foo"))))
  }

  @Test
  fun garbageIsNull() {
    assertThat(PreviewLink.parse("")).isNull()
    assertThat(PreviewLink.parse("ftp://h/a.rc")).isNull()
    assertThat(PreviewLink.parse("https://h/somewhere")).isNull()
    assertThat(PreviewLink.parse("https://h/ui-builder/")).isNull()
  }

  @Test
  fun sessionScopedStreamUrlRoundTrips() {
    val link = SessionLink("h", 443, "t", SessionTarget.Preview("p", "native-1"), secure = true)
    assertThat(link.webSocketUrl()).isEqualTo("wss://h:443/native-1/ws/p?token=t")
    assertThat(SessionLink.parse(link.webSocketUrl())).isEqualTo(link)
    assertThat(SessionLink.parse(link.toUri())).isEqualTo(link)
  }

  @Test
  fun tokenlessStreamOmitsTheQuery() {
    val link = SessionLink("h", 80, "", SessionTarget.Preview("p", "s"))
    assertThat(link.webSocketUrl()).isEqualTo("ws://h:80/s/ws/p")
  }

  @Test
  fun deviceProfileOverridesAndEnvironment() {
    val device =
      DeviceProfile(1080, 2400, 2.625f, fontScale = 1.3f, dark = true, localeTag = "de-DE")
    assertThat(device.widthDp).isEqualTo(411)
    assertThat(device.heightDp).isEqualTo(914)
    assertThat(device.streamOverrides())
      .containsExactly(
        "widthPx",
        "1080",
        "heightPx",
        "2400",
        "density",
        "2.625",
        "fontScale",
        "1.3",
        "uiMode",
        "dark",
        "localeTag",
        "de-DE",
      )
  }
}
