package ee.schimke.composeai.buildlogic

import com.ncorti.ktfmt.gradle.KtfmtExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Base conventions applied by every module: ktfmt with Google style. Applied explicitly via
 * `plugins { id("composeai.base-conventions") }`.
 *
 * Living in `build-logic` lets the ktfmt extension be configured with its real type
 * (`extensions.configure<KtfmtExtension>`) rather than reflectively — this convention plugin's
 * classpath carries both ktfmt and the Kotlin Gradle plugin it links against.
 */
class ComposeAiBaseConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("com.ncorti.ktfmt.gradle")
    project.extensions.configure<KtfmtExtension>("ktfmt") { googleStyle() }
  }
}
