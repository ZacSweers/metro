// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
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

plugins.withType<NodeJsRootPlugin> { the<NodeJsEnvSpec>().apply { version.set("24.4.1") } }
