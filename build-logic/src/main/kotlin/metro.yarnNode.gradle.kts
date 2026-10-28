// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec

plugins.withType<YarnPlugin> {
  the<YarnRootEnvSpec>().apply {
    version.set("1.22.22")
    yarnLockAutoReplace.set(true)
    installationDirectory.set(projectDir)
    ignoreScripts.set(false)
  }
}

plugins.withType<NodeJsRootPlugin> {
  the<NodeJsEnvSpec>().version.set("24.4.1")
  // Karma and Kotlin's source map plugin must load the same webpack instance.
  val webpack = the<NodeJsRootExtension>().versions.webpack
  the<NpmExtension>().override(webpack.name, webpack.version)
}
