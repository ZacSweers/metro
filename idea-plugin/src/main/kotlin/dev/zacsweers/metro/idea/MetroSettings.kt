// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

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
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.layout.ComponentPredicate
import dev.zacsweers.metro.idea.index.MetroResolutionService

class MetroSettingsState : BaseState() {
  /** Suppresses unused-declaration warnings for declarations Metro consumes via generated code. */
  var suppressUnusedWarnings by property(true)

  /** Suppresses IntelliJ's false-positive kapt configuration warning in Metro-enabled modules. */
  var suppressKaptConfigurationWarning by property(true)

  /** Shows binding navigation in the editor without disabling graph browsing or validation. */
  var enableBindingResolution by property(true)

  /** Keeps graph and binding data current as project code changes. */
  var automaticallyRefreshGraphData by property(true)

  /** Also resolve bindings from compiled dependencies (inject classes, contribution hints). */
  var resolveFromLibraries by property(true)

  /** `assisted` inlay hint next to implicitly assisted parameters, like Circuit-provided ones. */
  var assistedParameterInlays by property(true)
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
    lateinit var resolutionSelected: ComponentPredicate
    row {
      val cell =
        checkBox("Show binding navigation (gutter icons, code vision, inlay hints)")
          .bindSelected(state::enableBindingResolution)
      resolutionSelected = cell.selected
    }
    row {
      checkBox("Resolve bindings from compiled dependencies")
        .bindSelected(state::resolveFromLibraries)
        .comment("Includes bindings contributed by compiled project dependencies")
    }
    row {
      checkBox("Automatically refresh graphs and bindings after code changes")
        .bindSelected(state::automaticallyRefreshGraphData)
        .comment("When disabled, use Refresh in the Metro tool window to update graph data")
    }
    indent {
      row {
        checkBox("Show \"assisted\" inlay hints")
          .bindSelected(state::assistedParameterInlays)
          .enabledIf(resolutionSelected)
          .comment(
            "Implicitly assisted parameters, such as Circuit-provided types, that have no @Assisted annotation in source"
          )
      }
    }
  }

  override fun apply() {
    super.apply()
    // Apply changes to library resolution and automatic graph refresh.
    project.service<MetroResolutionService>().settingsChanged()
    // Apply editor display settings without waiting for a source edit.
    project.service<MetroDaemonRestartService>().requestRestart(inUnitTests = true)
  }
}
