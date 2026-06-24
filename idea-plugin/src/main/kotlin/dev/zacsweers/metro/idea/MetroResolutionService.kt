// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.circuit.CircuitClassIds
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphNode
import java.util.Collections
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtFile

/**
 * Shared resolution service powering Metro's line markers, code vision, and inlay hints.
 *
 * Builds a project-wide [dev.zacsweers.metro.idea.model.BindingIndex] from Kotlin stub indexes plus
 * the K2 Analysis API and caches it by Metro option fingerprint. Declaration shards are reusable
 * across modules; resolution runs through use-site graph contexts so editor features model the same
 * kind of graph membership that future shared validation will need.
 */
@Service(Service.Level.PROJECT)
class MetroResolutionService(private val project: Project) {
  // Project-wide indexes deduped by options fingerprint: projects typically share one Metro
  // config across modules, so caching per module would build and retain the same index once per
  // edited module. LRU-bounded so fingerprints orphaned by options changes don't retain their
  // last aggregate index forever.
  private val indexCaches: MutableMap<List<String>, CachedValue<BindingIndex>> =
    Collections.synchronizedMap(
      object : LinkedHashMap<List<String>, CachedValue<BindingIndex>>(8, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<List<String>, CachedValue<BindingIndex>>
        ): Boolean = size > 4
      }
    )

  /**
   * Returns the cached binding index for the module owning [element], or [BindingIndex.EMPTY] when
   * Metro is disabled or the element has no module.
   *
   * Must be called under a read action — building the index performs Analysis API resolution.
   * Normally that happens on background highlighting passes; EDT analysis is permitted for the
   * platform flows (and tests) that compute markers on the EDT.
   */
  internal fun index(element: PsiElement): BindingIndex {
    if (!MetroSettings.getInstance(project).state.enableBindingResolution) {
      return BindingIndex.EMPTY
    }
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return BindingIndex.EMPTY
    val state = project.service<MetroIdeProjectService>().state(module)
    if (!state.options.enabled) return BindingIndex.EMPTY
    val options = state.options
    return allowAnalysisOnEdt {
      indexCaches
        .computeIfAbsent(state.optionsFingerprint) {
          CachedValuesManager.getManager(project).createCachedValue {
            // The aggregate invalidates on any PSI change (out-of-block trackers are
            // platform-internal Analysis API), but per-file shards only re-analyze the files that
            // actually changed; a rebuild is mostly cache hits plus a merge.
            CachedValueProvider.Result.create(
              buildProjectIndex(options),
              PsiModificationTracker.MODIFICATION_COUNT,
              KotlinCompilerSettingsTracker.getInstance(project),
              MetroSettings.getInstance(project).asModificationTracker(),
            )
          }
        }
        .value
    }
  }

  /**
   * Aggregates per-file shards (each cached against its own file's modification stamp) and runs the
   * cross-file passes on the merged result.
   */
  private fun buildProjectIndex(options: MetroOptions): BindingIndex {
    val bindings = mutableListOf<KaBinding>()
    val consumers = mutableListOf<ConsumerEntry>()
    val graphs = mutableListOf<KaGraphNode>()
    val contributions = mutableListOf<ContributionEntry>()
    val assistedSites = mutableListOf<AssistedSite>()
    val bindingContainers = mutableListOf<BindingContainerEntry>()
    for (file in candidateFiles(options)) {
      ProgressManager.checkCanceled()
      val shard = shardFor(file)
      bindings += shard.bindings
      consumers += shard.consumers
      graphs += shard.graphs
      contributions += shard.contributions
      assistedSites += shard.assistedSites
      bindingContainers += shard.bindingContainers
    }
    if (MetroSettings.getInstance(project).state.resolveFromLibraries) {
      IndexBuilder(project, options, bindings, consumers, graphs, contributions).postProcess()
    }
    return BindingIndex(
      bindings,
      consumers,
      graphs,
      contributions,
      assistedSites,
      bindingContainers,
    )
  }

  /** Files containing any Metro-relevant annotation by short name, via stub indexes. */
  private fun candidateFiles(options: MetroOptions): Set<KtFile> {
    val shortNames =
      projectSweepAnnotationIds(options).mapTo(sortedSetOf()) { it.shortClassName.asString() }
    val searchScope = GlobalSearchScope.projectScope(project)
    val files = LinkedHashSet<KtFile>()
    for (shortName in shortNames) {
      ProgressManager.checkCanceled()
      for (entry in KotlinAnnotationsIndex[shortName, project, searchScope]) {
        files += entry.containingKtFile
      }
    }
    return files
  }

  private fun projectSweepAnnotationIds(fallbackOptions: MetroOptions): Set<ClassId> {
    val ids = linkedSetOf<ClassId>()
    ids += sweepAnnotationIds(fallbackOptions)
    val service = project.service<MetroIdeProjectService>()
    for (module in ModuleManager.getInstance(project).modules) {
      val state = service.state(module)
      if (state.options.enabled) {
        ids += sweepAnnotationIds(state.options)
      }
    }
    return ids
  }

  private fun shardFor(file: KtFile): FileShard {
    return CachedValuesManager.getCachedValue(file) {
      // Shards use the owning file's module options, so files keep their own module's semantics
      // even when the requesting module's config differs.
      val state = file.metroIdeState()
      val shard =
        if (state.options.enabled) {
          IndexBuilder(file.project, state.options).buildShard(file)
        } else {
          FileShard.EMPTY
        }
      CachedValueProvider.Result.create(
        shard,
        file,
        KotlinCompilerSettingsTracker.getInstance(file.project),
      )
    }
  }
}

private fun sweepAnnotationIds(options: MetroOptions): Set<ClassId> {
  return buildSet {
    addAll(options.providesAnnotations)
    addAll(options.bindsAnnotations)
    addAll(options.multibindsAnnotations)
    addAll(options.injectAnnotations)
    addAll(options.assistedInjectAnnotations)
    addAll(options.allContributesAnnotations)
    addAll(options.dependencyGraphAnnotations)
    addAll(options.graphExtensionAnnotations)
    addAll(options.assistedFactoryAnnotations)
    addAll(options.bindingContainerAnnotations)
    addAll(bindsOptionalOfAnnotations(options))
    add(CircuitClassIds.CircuitInject)
  }
}
