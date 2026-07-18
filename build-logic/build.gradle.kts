plugins { `kotlin-dsl` }

kotlin { jvmToolchain(17) }

dependencies {
  implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
  // ktfmt is applied + configured (googleStyle) on every module by `ComposeAiBaseConventionsPlugin`
  // — its plugin marker puts `KtfmtExtension` on this convention-plugin classpath (alongside the
  // Kotlin Gradle plugin above, which ktfmt links against), so the style is configured with the
  // real type rather than reflectively.
  implementation(
    "com.ncorti.ktfmt.gradle:com.ncorti.ktfmt.gradle.gradle.plugin:${libs.versions.ktfmt.get()}"
  )
}

gradlePlugin {
  plugins {
    register("composeAiBaseConventions") {
      id = "composeai.base-conventions"
      implementationClass = "ee.schimke.composeai.buildlogic.ComposeAiBaseConventionsPlugin"
    }
    register("composeAiAndroidConventions") {
      id = "composeai.android-conventions"
      implementationClass = "ee.schimke.composeai.buildlogic.ComposeAiAndroidConventionsPlugin"
    }
  }
}
