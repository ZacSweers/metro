// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins { `kotlin-dsl` }

dependencies {
  implementation(libs.kotlin.gradlePlugin)
  implementation(libs.android.gradlePlugin)
  implementation(libs.plugins.mavenPublish.get().run { "$pluginId:$pluginId.gradle.plugin:$version" })
  implementation(libs.plugins.dokka.get().run { "$pluginId:$pluginId.gradle.plugin:$version" })
  implementation(libs.plugins.spotless.get().run { "$pluginId:$pluginId.gradle.plugin:$version" })
}
