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
import dev.zacsweers.metro.idea.graph.auto.MetroPinnedGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.tracing.MetroIdeTracingService

class MetroSettingsState : BaseState() {
  /** Suppresses unused-declaration warnings for declarations Metro consumes via generated code. */
  var suppressUnusedWarnings by property(true)

  /** Suppresses IntelliJ's false-positive kapt configuration warning in Metro-enabled modules. */
  var suppressKaptConfigurationWarning by property(true)

  /** Shows binding navigation in the editor without disabling graph browsing or validation. */
  var enableBindingResolution by property(true)

  /** Opts into refreshing graph and binding data as project code changes. */
  var automaticallyRefreshGraphData by property(false)

  /** Validates the pinned graph after edits while automatic graph refresh is enabled. */
  var automaticallyValidatePinnedGraph by property(false)

  /** Also resolve bindings from compiled dependencies (inject classes, contribution hints). */
  var resolveFromLibraries by property(true)

  /** `assisted` inlay hint next to implicitly assisted parameters, like Circuit-provided ones. */
  var assistedParameterInlays by property(true)

  /** Exposes local performance tracing controls for investigating plugin behavior. */
  var enableDebuggingOptions by property(false)

  /**
   * Adds thread execution slices and coroutine flows to captures started with this option enabled.
   */
  var includeThreadActivity by property(false)
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

  /** Keeps each dependent control beside the option that enables it. */
  override fun createPanel() = panel {
    val state = MetroSettings.getInstance(project).state
    group("Editor") {
      lateinit var resolutionSelected: ComponentPredicate
      row {
        val cell =
          checkBox("Show binding navigation (gutter icons, code vision, inlay hints)")
            .bindSelected(state::enableBindingResolution)
        resolutionSelected = cell.selected
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
    group("Graphs and bindings") {
      row {
        checkBox("Resolve bindings from compiled dependencies")
          .bindSelected(state::resolveFromLibraries)
          .comment("Includes bindings contributed by compiled project dependencies")
      }
      lateinit var automaticRefreshSelected: ComponentPredicate
      row {
        val cell =
          checkBox("Automatically refresh graphs and bindings after code changes")
            .bindSelected(state::automaticallyRefreshGraphData)
            .comment("When disabled, use Refresh in the Metro tool window to update graph data")
        automaticRefreshSelected = cell.selected
      }
      indent {
        row {
          checkBox("Automatically validate the pinned graph after code changes")
            .bindSelected(state::automaticallyValidatePinnedGraph)
            .enabledIf(automaticRefreshSelected)
            .comment(
              "Checks the pinned graph and its children after a short pause; requires automatic graph refresh"
            )
        }
      }
    }
    group("Warnings") {
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
    group("Debugging/Experimental") {
      lateinit var debuggingSelected: ComponentPredicate
      row {
        val cell =
          checkBox("Enable debugging options")
            .bindSelected(state::enableDebuggingOptions)
            .comment(
              "Shows local performance tracing actions in Find Action and the Metro tool window"
            )
        debuggingSelected = cell.selected
      }
      indent {
        row {
            checkBox("Include thread activity")
              .bindSelected(state::includeThreadActivity)
              .comment(
                "Adds thread slices and coroutine arrows to new captures; increases recording overhead"
              )
          }
          .visibleIf(debuggingSelected)
      }
    }
  }

  override fun apply() {
    super.apply()
    applyMetroSettings(project)
  }
}

/** Applies settings from the preferences panel and the tool-window refresh selector. */
internal fun applyMetroSettings(project: Project) {
  project.service<MetroIdeTracingService>().settingsChanged()
  project.service<MetroResolutionService>().settingsChanged()
  project.service<MetroPinnedGraphValidationService>().requestValidation()
  // Apply editor display settings without waiting for a source edit.
  project.service<MetroDaemonRestartService>().requestRestart(inUnitTests = true)
}
