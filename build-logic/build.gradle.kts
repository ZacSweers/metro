// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  `kotlin-dsl`
}

kotlin { jvmToolchain(libs.versions.jdk.get().toInt()) }

dependencies {
  implementation(libs.kotlin.gradlePlugin)
  implementation(libs.android.gradlePlugin)
  implementation(
    libs.plugins.mavenPublish.get().run { "$pluginId:$pluginId.gradle.plugin:$version" }
  )
  implementation(libs.plugins.dokka.get().run { "$pluginId:$pluginId.gradle.plugin:$version" })
  implementation(libs.plugins.spotless.get().run { "$pluginId:$pluginId.gradle.plugin:$version" })
  // Force the latest R8 to match what we use the minified JMH tests
  implementation(libs.r8)
}
