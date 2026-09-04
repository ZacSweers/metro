// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.idea.MetroCompilerSettingsTracker
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingIndexModuleView
import dev.zacsweers.metro.idea.model.BindingIndexResolutionInputs
import dev.zacsweers.metro.idea.model.DeclarationDisplay
import dev.zacsweers.metro.idea.model.FileOrdinal
import dev.zacsweers.metro.idea.model.FileOrdinalTable
import dev.zacsweers.metro.idea.model.ModuleViewId
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider

/**
 * Reuses immutable module visibility across coordinator builds with the same file membership. Calls
 * run serially inside the coordinator's read action. Cached entries contain no declarations.
 */
internal class ModuleVisibilityCapture(private val project: Project) {
  private var projectInputs: IndexInputs? = null
  private val topologies =
    object : LinkedHashMap<ModuleVisibilityKey, ModuleVisibilityTopology>(4, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<ModuleVisibilityKey, ModuleVisibilityTopology>
      ): Boolean = size > MAX_TOPOLOGIES
    }

  /** Attaches fresh pointer ranges to a reusable file and module topology. */
  @OptIn(KaPlatformInterface::class)
  fun capture(
    representatives: Map<VirtualFile, PsiElement>,
    pointerSourceIdentities: Map<SmartPsiElementPointer<*>, BindingIndex.SourcePointerIdentity>,
    declarationDisplays: Map<SmartPsiElementPointer<*>, DeclarationDisplay>,
  ): BindingIndexResolutionInputs {
    val inputs =
      IndexInputs(
        roots = ProjectRootModificationTracker.getInstance(project).modificationCount,
        compilerSettings = project.service<MetroCompilerSettingsTracker>().modificationCount,
      )
    if (inputs != projectInputs) {
      topologies.clear()
      projectInputs = inputs
    }

    val owners = linkedMapOf<VirtualFile, ModuleFileOwner>()
    val moduleRepresentatives = linkedMapOf<KaModule, PsiElement>()
    for ((file, element) in representatives) {
      ProgressManager.checkCanceled()
      val module = KaModuleProvider.getModule(project, element, useSiteModule = null)
      // Moving a file can change visibility without changing roots or its modification stamp.
      owners[file] = ModuleFileOwner(module, file.url)
      moduleRepresentatives.putIfAbsent(module, element)
    }
    val daggerInterop = linkedMapOf<KaModule, Boolean>()
    for ((module, element) in moduleRepresentatives) {
      ProgressManager.checkCanceled()
      daggerInterop[module] = element.metroIdeState().options.enableDaggerAnvilInterop
    }

    val key = ModuleVisibilityKey(owners, daggerInterop)
    var topology = topologies[key]
    if (topology == null) {
      topology = buildTopology(representatives, key)
      topologies[key] = topology
    }
    return BindingIndexResolutionInputs(
      topology.fileOrdinalTable,
      topology.moduleByFile,
      topology.moduleViews,
      pointerSourceIdentities,
      declarationDisplays,
    )
  }

  /** Clears retained module references when the owning coordinator stops. */
  fun clear() {
    projectInputs = null
    topologies.clear()
  }

  @OptIn(KaPlatformInterface::class)
  private fun buildTopology(
    representatives: Map<VirtualFile, PsiElement>,
    key: ModuleVisibilityKey,
  ): ModuleVisibilityTopology {
    val fileOrdinals = linkedMapOf<VirtualFile, FileOrdinal>()
    val moduleIds = linkedMapOf<KaModule, ModuleViewId>()
    val moduleByFile = linkedMapOf<VirtualFile, ModuleViewId>()
    for ((file, owner) in key.owners) {
      ProgressManager.checkCanceled()
      fileOrdinals[file] = FileOrdinal(fileOrdinals.size)
      moduleByFile[file] = moduleIds.getOrPut(owner.module) { ModuleViewId(moduleIds.size) }
    }
    val fileOrdinalTable = FileOrdinalTable.freeze(fileOrdinals)
    val moduleViews = linkedMapOf<ModuleViewId, BindingIndexModuleView>()
    for ((module, moduleId) in moduleIds) {
      ProgressManager.checkCanceled()
      val scope = KaResolutionScope.forModule(module)
      val visibleFiles = BooleanArray(fileOrdinalTable.size)
      for ((file, element) in representatives) {
        ProgressManager.checkCanceled()
        if (scope.contains(element)) {
          visibleFiles[fileOrdinalTable.getValue(file).value] = true
        }
      }
      moduleViews[moduleId] =
        BindingIndexModuleView(
          id = moduleId,
          module = module,
          visibleFileOrdinals = visibleFiles,
          fileOrdinalTable = fileOrdinalTable,
          daggerAnvilInteropEnabled = key.daggerInterop.getValue(module),
        )
    }
    return ModuleVisibilityTopology(fileOrdinalTable, moduleByFile, moduleViews)
  }

  private companion object {
    const val MAX_TOPOLOGIES = 4
  }
}

private data class ModuleFileOwner(val module: KaModule, val url: String)

private data class ModuleVisibilityKey(
  val owners: Map<VirtualFile, ModuleFileOwner>,
  val daggerInterop: Map<KaModule, Boolean>,
)

private class ModuleVisibilityTopology(
  val fileOrdinalTable: FileOrdinalTable,
  val moduleByFile: Map<VirtualFile, ModuleViewId>,
  val moduleViews: Map<ModuleViewId, BindingIndexModuleView>,
)
