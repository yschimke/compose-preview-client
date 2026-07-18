// Root build. The per-module conventions (ktfmt/googleStyle, Android compileSdk/minSdk/Java 17) live
// in the vendored `build-logic` convention plugins, applied explicitly by each module. The shared
// plugins are declared here `apply false` so each resolves once on the root classpath and the
// modules share a single ClassLoader for them.
plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.preview) apply false
  // Gradle Play Publisher — applied by :mobile / :wear for release publishing to the Play internal
  // track. Declared here so it resolves once on the root classpath, mirroring the other plugins.
  alias(libs.plugins.play.publisher) apply false
}

// Aggregate ktfmt tasks over the three modules (each applies `composeai.base-conventions`, which
// registers `ktfmtCheck` / `ktfmtFormat`). Used by the pre-commit hook and CI.
tasks.register("ktfmtCheckAll") {
  group = "verification"
  description = "Runs ktfmtCheck across every module."
  dependsOn(subprojects.map { "${it.path}:ktfmtCheck" })
}

tasks.register("ktfmtFormatAll") {
  group = "formatting"
  description = "Runs ktfmtFormat across every module."
  dependsOn(subprojects.map { "${it.path}:ktfmtFormat" })
}
