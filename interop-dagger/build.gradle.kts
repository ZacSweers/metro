plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.mavenPublish)
  alias(libs.plugins.dokka)
}

dependencies {
  api(project(":runtime"))
  api(libs.dagger.runtime)
  implementation(libs.atomicfu)
}