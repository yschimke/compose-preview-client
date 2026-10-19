// The on-device half of a device preview, shared by the phone and watch apps: plays Remote Compose
// documents with the AndroidX player, measures the display a preview is shown on, reads local
// `.rc` / `.uid` files, and drives `:core`'s preview engines from Compose. Foundation-only, so each
// app keeps its own Material chrome on top.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ee.schimke.composeai.clients.player"
  compileSdk = 37
  // The AndroidX Remote Compose player's own floor, and the phone app's.
  defaultConfig { minSdk = 26 }
  buildFeatures { compose = true }
}

dependencies {
  api(project(":core"))

  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)

  // The same player a host app embeds, so the device draws exactly what would ship.
  implementation(libs.remote.core)
  implementation(libs.remote.player.core)
  implementation(libs.remote.player.view)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
