// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.facet.Facet
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker

/**
 * Tracks every source of module compiler options. Facet updates can leave Kotlin's project settings
 * tracker unchanged, so all option-dependent caches share this combined revision.
 */
@Service(Service.Level.PROJECT)
internal class MetroCompilerSettingsTracker(project: Project) : ModificationTracker, Disposable {
  private val kotlinSettings = KotlinCompilerSettingsTracker.getInstance(project)
  private val facetChanges = SimpleModificationTracker()

  init {
    project.messageBus
      .connect(this)
      .subscribe(
        FacetManager.FACETS_TOPIC,
        object : FacetManagerListener {
          override fun facetAdded(facet: Facet<*>) = facetChanges.incModificationCount()

          override fun facetRemoved(facet: Facet<*>) = facetChanges.incModificationCount()

          override fun facetConfigurationChanged(facet: Facet<*>) =
            facetChanges.incModificationCount()
        },
      )
  }

  override fun getModificationCount(): Long =
    kotlinSettings.modificationCount + facetChanges.modificationCount

  override fun dispose() = Unit
}
