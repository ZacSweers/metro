// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class MetroSettingsState : BaseState() {
  /** Suppresses unused-declaration warnings for declarations Metro consumes via generated code. */
  var suppressUnusedWarnings by property(true)

  /** Suppresses IntelliJ's false-positive kapt configuration warning in Metro-enabled modules. */
  var suppressKaptConfigurationWarning by property(true)
}

/** Project-level Metro IDE settings, stored in `.idea/metro.xml` so teams can check them in. */
@Service(Service.Level.PROJECT)
@State(name = "MetroSettings", storages = [Storage("metro.xml")])
class MetroSettings : SimplePersistentStateComponent<MetroSettingsState>(MetroSettingsState()) {
  companion object {
    fun getInstance(project: Project): MetroSettings = project.service()
  }
}

class MetroSettingsConfigurable(private val project: Project) : BoundConfigurable("Metro") {

  override fun createPanel() = panel {
    val state = MetroSettings.getInstance(project).state
    row {
      checkBox("Suppress unused-declaration warnings for Metro-injected declarations")
        .bindSelected(state::suppressUnusedWarnings)
        .comment(
          "Treats providers, injected classes, and contributions as used even when their only " +
            "usages are in generated code"
        )
    }
    row {
      checkBox("Suppress false-positive kapt configuration warnings")
        .bindSelected(state::suppressKaptConfigurationWarning)
        .comment("Metro does not require kapt; applies only to modules with Metro enabled")
    }
  }

  override fun apply() {
    super.apply()
    // Re-run highlighting so the gates take effect without further edits
    DaemonCodeAnalyzer.getInstance(project).restart()
  }
}
