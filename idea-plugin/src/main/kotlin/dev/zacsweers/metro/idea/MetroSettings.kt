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
import com.intellij.openapi.util.ModificationTracker
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

class MetroSettingsState : BaseState() {
  /** Suppresses unused-declaration warnings for declarations Metro consumes via generated code. */
  var suppressUnusedWarnings by property(true)

  /** Master switch for binding resolution: gutter icons, code vision, and inlay hints. */
  var enableBindingResolution by property(true)

  /** Also resolve bindings from compiled dependencies (inject classes, contribution hints). */
  var resolveFromLibraries by property(true)

  /** `assisted` inlay hint next to implicitly assisted (e.g. Circuit-provided) parameters. */
  var assistedParameterInlays by property(true)
}

/** Project-level Metro IDE settings, stored in `.idea/metro.xml` so teams can check them in. */
@Service(Service.Level.PROJECT)
@State(name = "MetroSettings", storages = [Storage("metro.xml")])
class MetroSettings : SimplePersistentStateComponent<MetroSettingsState>(MetroSettingsState()) {
  companion object {
    fun getInstance(project: Project): MetroSettings = project.service()
  }

  internal fun asModificationTracker(): ModificationTracker {
    return ModificationTracker { state.modificationCount }
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
    lateinit var resolutionSelected: com.intellij.ui.layout.ComponentPredicate
    row {
      val cell =
        checkBox("Show binding navigation (gutter icons, code vision, inlay hints)")
          .bindSelected(state::enableBindingResolution)
      resolutionSelected = cell.selected
    }
    indent {
      row {
        checkBox("Resolve bindings from compiled dependencies")
          .bindSelected(state::resolveFromLibraries)
          .enabledIf(resolutionSelected)
          .comment("Scans library metadata for injected classes and contribution hints")
      }
      row {
        checkBox("Show \"assisted\" inlay hints")
          .bindSelected(state::assistedParameterInlays)
          .enabledIf(resolutionSelected)
          .comment(
            "Implicitly assisted parameters, e.g. Circuit-provided types, that have no @Assisted annotation in source"
          )
      }
    }
  }

  override fun apply() {
    super.apply()
    // Re-run highlighting so the gates take effect without further edits
    DaemonCodeAnalyzer.getInstance(project).restart()
  }
}
