pluginManagement {
  // Trimmed convention plugins (`composeai.base-conventions`, `composeai.android-conventions`)
  // vendored from yschimke/compose-ai-tools.
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }

  // The compose-preview render plugin (`ee.schimke.composeai.preview`) publishes its main artifact
  // to Maven Central but NOT a Gradle plugin-marker, so `plugins { id(...) version ... }` can't
  // resolve it directly. Map the plugin id onto the real artifact coordinates. See
  // `gradle/libs.versions.toml` (`compose-preview-plugin`).
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id == "ee.schimke.composeai.preview") {
        useModule("ee.schimke.composeai:compose-preview-plugin:${requested.version}")
      }
    }
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "compose-preview-client"

// The session-viewer clients for `compose-preview serve`'s streamed-frame lane.
// `:core` is the pure-JVM engine (wire protocol + connection state machine + session-link parser +
// mDNS discovery contract); `:mobile` / `:wear` are the thin Android / Wear OS shells on top. See
// docs/SESSION-VIEWER.md and the versioned wire contract owned by yschimke/compose-ai-tools.
include(":core")
include(":mobile")
include(":wear")
