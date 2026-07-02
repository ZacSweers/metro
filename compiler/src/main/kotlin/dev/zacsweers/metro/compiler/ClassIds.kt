// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.name.ClassId

public class ClassIds(private val options: MetroOptions = MetroOptions()) {
  public companion object {
    public fun fromOptions(options: MetroOptions): ClassIds = ClassIds(options)
  }

  // Graphs
  internal val dependencyGraphAnnotation = MetroClassIds.dependencyGraph
  internal val dependencyGraphAnnotations = options.dependencyGraphAnnotations
  internal val dependencyGraphFactoryAnnotations = options.dependencyGraphFactoryAnnotations

  // Assisted inject
  internal val assistedInjectAnnotations = options.assistedInjectAnnotations
  internal val metroAssisted = MetroClassIds.assisted
  internal val assistedAnnotations = options.assistedAnnotations
  internal val metroAssistedFactory = MetroClassIds.assistedFactory
  internal val assistedFactoryAnnotations = options.assistedFactoryAnnotations

  internal val injectAnnotations = options.injectAnnotations

  internal val allInjectAnnotations = options.allInjectAnnotations

  internal val qualifierAnnotations = options.qualifierAnnotations
  internal val scopeAnnotations = options.scopeAnnotations
  internal val bindingContainerAnnotations = options.bindingContainerAnnotations

  internal val originAnnotations = options.originAnnotations

  internal val defaultBindingAnnotation = MetroClassIds.defaultBinding

  internal val graphPrivateAnnotation = MetroClassIds.graphPrivate

  internal val exposeImplBindingAnnotation = MetroClassIds.exposeImplBinding

  internal val contributionProviderExclusionAnnotations =
    options.contributionProviderExclusionAnnotations

  internal val optionalBindingAnnotations = options.optionalBindingAnnotations

  internal val bindsAnnotations = options.bindsAnnotations

  internal val providesAnnotations = options.providesAnnotations

  // Multibindings
  internal val intoSetAnnotations = options.intoSetAnnotations
  internal val elementsIntoSetAnnotations = options.elementsIntoSetAnnotations
  internal val mapKeyAnnotations = options.mapKeyAnnotations
  internal val intoMapAnnotations = options.intoMapAnnotations
  internal val multibindsAnnotations = options.multibindsAnnotations

  internal val contributesToAnnotations = options.contributesToAnnotations
  internal val contributesBindingAnnotations = options.contributesBindingAnnotations
  internal val contributesIntoSetAnnotations = options.contributesIntoSetAnnotations
  internal val customContributesIntoSetAnnotations = options.customContributesIntoSetAnnotations
  internal val contributesIntoMapAnnotations = options.contributesIntoMapAnnotations
  internal val graphExtensionAnnotations = options.graphExtensionAnnotations
  internal val graphExtensionFactoryAnnotations = options.graphExtensionFactoryAnnotations
  internal val allGraphExtensionAndFactoryAnnotations =
    graphExtensionAnnotations + graphExtensionFactoryAnnotations

  internal val allContributesAnnotations = options.allContributesAnnotations

  /**
   * Repeatable annotations in compiled sources behave interestingly. They get an implicit
   * `Container` nested class that has an array value of the repeated annotations. For example:
   * `ContributesBinding.Container`
   *
   * Note that not all of these may actually be repeatable, but this doesn't need to resolve it for
   * sure and is just a general catch-all.
   */
  internal val allRepeatableContributesAnnotationsContainers =
    allContributesAnnotations.toContainerAnnotations()

  internal val allContributesAnnotationsWithContainers =
    allContributesAnnotations + allRepeatableContributesAnnotationsContainers

  internal val contributesBindingAnnotationsWithContainers =
    contributesBindingAnnotations + contributesBindingAnnotations.toContainerAnnotations()

  /** All binding-like contributes annotations (everything except `@ContributesTo`). */
  internal val contributesBindingLikeAnnotations = options.contributesBindingLikeAnnotations

  internal val contributesBindingLikeAnnotationsWithContainers =
    contributesBindingLikeAnnotations + contributesBindingLikeAnnotations.toContainerAnnotations()

  private fun Set<ClassId>.toContainerAnnotations() = mapToSet {
    it.createNestedClassId(Symbols.Names.Container)
  }

  internal val graphLikeAnnotations = dependencyGraphAnnotations + graphExtensionAnnotations
  internal val graphFactoryLikeAnnotations =
    dependencyGraphFactoryAnnotations + graphExtensionFactoryAnnotations

  /**
   * Class-level annotations that act like @Inject for code gen purposes. This includes @Inject and
   * all @Contributes* annotations (ContributesBinding, ContributesIntoSet, ContributesIntoMap)
   * since they implicitly make a class injectable.
   *
   * Notes:
   * - `ContributesTo` is excluded since it's interface-only and doesn't make a class injectable.
   * - This should NOT be used for constructor/function/member injection sites.
   * - The inclusion of @Contributes* annotations can be controlled by the `contributesAsInject`
   *   option.
   */
  internal val injectLikeAnnotations = options.injectLikeAnnotations

  internal val providerTypes = options.providerTypes

  internal val suspendProviderTypes = buildSet {
    add(Symbols.ClassIds.metroSuspendProvider)
    if (options.enableFunctionProviders) {
      add(Symbols.ClassIds.suspendFunction0)
    }
  }

  internal val suspendLazyTypes = setOf(Symbols.ClassIds.metroSuspendLazy)

  internal val nonFunctionProviderTypes by memoize { providerTypes - MetroClassIds.function0 }

  internal val lazyTypes = options.lazyTypes

  internal val includes = setOf(MetroClassIds.includes)

  internal val allCustomAnnotations = options.allCustomClassIds
}
