// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.circuit.CircuitClassIds
import dev.zacsweers.metro.idea.index.bindsOptionalOfAnnotations
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtFile

/** Module groups that can share one options-specific binding index. */
internal data class ResolutionSnapshotTarget(
  val key: SnapshotKey,
  val modules: List<Module>,
)

/** Frozen source work captured by the coordinator for one snapshot attempt. */
internal data class SourceSnapshotChanges(
  val dirty: Set<VirtualFile>,
  val requested: Set<VirtualFile>,
  val forceRebuildFiles: Set<VirtualFile>,
  val forceAll: Boolean,
) {
  /** Rebuilds structurally changed files and their owners even when cached PSI stamps match. */
  fun forcesRebuild(file: VirtualFile): Boolean = forceAll || file in forceRebuildFiles
}

/** Options and dependency-resolution mode shared by one index target group. */
internal data class SnapshotKey(
  val fingerprint: IndexOptionsFingerprint,
  val resolveFromLibraries: Boolean,
)

/** Platform input versions captured together with source declarations. */
internal data class IndexInputs(val roots: Long, val compilerSettings: Long)

/** Parsed compiler-option values that can actually change an IDE declaration snapshot. */
internal class IndexOptionsFingerprint(val options: MetroOptions) {
  private val annotationGroups =
    listOf(
      options.dependencyGraphAnnotations,
      options.dependencyGraphFactoryAnnotations,
      options.graphExtensionAnnotations,
      options.graphExtensionFactoryAnnotations,
      options.injectAnnotations,
      options.assistedInjectAnnotations,
      options.assistedAnnotations,
      options.assistedFactoryAnnotations,
      options.contributionProviderExclusionAnnotations,
      options.providesAnnotations,
      options.bindsAnnotations,
      options.multibindsAnnotations,
      options.allContributesAnnotations,
      options.contributesBindingAnnotations,
      options.contributesIntoSetAnnotations,
      options.customContributesIntoSetAnnotations,
      options.contributesIntoMapAnnotations,
      options.bindingContainerAnnotations,
      options.intoSetAnnotations,
      options.elementsIntoSetAnnotations,
      options.intoMapAnnotations,
      options.mapKeyAnnotations,
      options.qualifierAnnotations,
      options.scopeAnnotations,
      options.originAnnotations,
      options.optionalBindingAnnotations,
    )

  private val wrapperGroups =
    listOf(
      options.providerTypes,
      options.lazyTypes,
      options.suspendProviderModelingTypes,
      options.suspendLazyTypes,
    )

  private val flags =
    listOf(
      options.contributesAsInject,
      options.generateContributionProviders,
      options.enableCircuitCodegen,
      options.enableDaggerRuntimeInterop,
      options.enableDaggerAnvilInterop,
      options.enableTopLevelFunctionInjection,
      options.enableSuspendProviders,
      options.enableFunctionProviders,
      options.shrinkUnusedBindings,
    )

  private val optionalBindingBehavior = options.optionalBindingBehavior

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is IndexOptionsFingerprint) return false
    return annotationGroups == other.annotationGroups &&
      wrapperGroups == other.wrapperGroups &&
      flags == other.flags &&
      optionalBindingBehavior == other.optionalBindingBehavior
  }

  override fun hashCode(): Int {
    var result = annotationGroups.hashCode()
    result = 31 * result + wrapperGroups.hashCode()
    result = 31 * result + flags.hashCode()
    result = 31 * result + optionalBindingBehavior.hashCode()
    return result
  }
}

/** Annotation types used to discover source files that may contribute to the index. */
internal fun sweepAnnotationIds(options: MetroOptions): Set<ClassId> {
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

/**
 * Includes local import aliases without resolving annotations or starting an Analysis API session.
 */
internal fun KtFile.annotationShortNamesIncludingAliases(annotationIds: Set<ClassId>): Set<String> {
  val names = mutableSetOf<String>()
  for (annotationId in annotationIds) {
    ProgressManager.checkCanceled()
    names += annotationId.shortClassName.asString()
  }
  for (directive in importDirectives) {
    ProgressManager.checkCanceled()
    val alias = directive.aliasName ?: continue
    val importedName = directive.importedFqName ?: continue
    for (annotationId in annotationIds) {
      ProgressManager.checkCanceled()
      if (annotationId.asSingleFqName() == importedName) {
        names += alias
        break
      }
    }
  }
  return names
}
