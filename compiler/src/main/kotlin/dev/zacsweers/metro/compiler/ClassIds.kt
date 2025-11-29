// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

public class ClassIds(
  customLazyClasses: Set<ClassId> = emptySet(),
  customProviderClasses: Set<ClassId> = emptySet(),
  customAssistedAnnotations: Set<ClassId> = emptySet(),
  customAssistedFactoryAnnotations: Set<ClassId> = emptySet(),
  customAssistedInjectAnnotations: Set<ClassId> = emptySet(),
  customBindsAnnotations: Set<ClassId> = emptySet(),
  customContributesToAnnotations: Set<ClassId> = emptySet(),
  customContributesBindingAnnotations: Set<ClassId> = emptySet(),
  internal val customContributesIntoSetAnnotations: Set<ClassId> = emptySet(),
  customGraphExtensionAnnotations: Set<ClassId> = emptySet(),
  customGraphExtensionFactoryAnnotations: Set<ClassId> = emptySet(),
  customElementsIntoSetAnnotations: Set<ClassId> = emptySet(),
  customGraphAnnotations: Set<ClassId> = emptySet(),
  customGraphFactoryAnnotations: Set<ClassId> = emptySet(),
  customInjectAnnotations: Set<ClassId> = emptySet(),
  customIntoMapAnnotations: Set<ClassId> = emptySet(),
  customIntoSetAnnotations: Set<ClassId> = emptySet(),
  customMapKeyAnnotations: Set<ClassId> = emptySet(),
  customMultibindsAnnotations: Set<ClassId> = emptySet(),
  customProvidesAnnotations: Set<ClassId> = emptySet(),
  customQualifierAnnotations: Set<ClassId> = emptySet(),
  customScopeAnnotations: Set<ClassId> = emptySet(),
  customBindingContainerAnnotations: Set<ClassId> = emptySet(),
  customOriginAnnotations: Set<ClassId> = emptySet(),
  customOptionalBindingAnnotations: Set<ClassId> = emptySet(),
  private val contributesAsInject: Boolean = false,
) {
  public companion object {
    public fun fromOptions(options: MetroOptions): ClassIds =
      ClassIds(
        customProviderClasses = options.customProviderTypes,
        customLazyClasses = options.customLazyTypes,
        customAssistedAnnotations = options.customAssistedAnnotations,
        customAssistedFactoryAnnotations = options.customAssistedFactoryAnnotations,
        customAssistedInjectAnnotations = options.customAssistedInjectAnnotations,
        customBindsAnnotations = options.customBindsAnnotations,
        customContributesToAnnotations = options.customContributesToAnnotations,
        customContributesBindingAnnotations = options.customContributesBindingAnnotations,
        customContributesIntoSetAnnotations = options.customContributesIntoSetAnnotations,
        customGraphExtensionAnnotations = options.customGraphExtensionAnnotations,
        customGraphExtensionFactoryAnnotations = options.customGraphExtensionFactoryAnnotations,
        customElementsIntoSetAnnotations = options.customElementsIntoSetAnnotations,
        customGraphAnnotations = options.customGraphAnnotations,
        customGraphFactoryAnnotations = options.customGraphFactoryAnnotations,
        customInjectAnnotations = options.customInjectAnnotations,
        customIntoMapAnnotations = options.customIntoMapAnnotations,
        customIntoSetAnnotations = options.customIntoSetAnnotations,
        customMapKeyAnnotations = options.customMapKeyAnnotations,
        customMultibindsAnnotations = options.customMultibindsAnnotations,
        customProvidesAnnotations = options.customProvidesAnnotations,
        customQualifierAnnotations = options.customQualifierAnnotations,
        customScopeAnnotations = options.customScopeAnnotations,
        customBindingContainerAnnotations = options.customBindingContainerAnnotations,
        customOriginAnnotations = options.customOriginAnnotations,
        customOptionalBindingAnnotations = options.customOptionalBindingAnnotations,
        contributesAsInject = options.contributesAsInject,
      )
  }

  private fun FqName.classIdOf(simpleName: String): ClassId {
    return classIdOf(Name.identifier(simpleName))
  }

  private fun FqName.classIdOf(simpleName: Name): ClassId {
    return ClassId(this, simpleName)
  }

  // Graphs
  internal val dependencyGraphAnnotation =
    Symbols.FqNames.metroRuntimePackage.classIdOf("DependencyGraph")
  internal val dependencyGraphAnnotations =
    setOf(dependencyGraphAnnotation) + customGraphAnnotations
  internal val dependencyGraphFactoryAnnotations =
    setOf(dependencyGraphAnnotation.createNestedClassId(Name.identifier("Factory"))) +
      customGraphFactoryAnnotations

  // Assisted inject
  internal val assistedInjectAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("AssistedInject")) +
      customAssistedInjectAnnotations
  private val metroAssisted = Symbols.FqNames.metroRuntimePackage.classIdOf("Assisted")
  internal val assistedAnnotations = setOf(metroAssisted) + customAssistedAnnotations
  internal val metroAssistedFactory =
    Symbols.FqNames.metroRuntimePackage.classIdOf("AssistedFactory")
  internal val assistedFactoryAnnotations =
    setOf(metroAssistedFactory) + customAssistedFactoryAnnotations

  internal val injectAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("Inject")) + customInjectAnnotations

  internal val allInjectAnnotations = injectAnnotations + assistedInjectAnnotations

  internal val qualifierAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("Qualifier")) + customQualifierAnnotations
  internal val scopeAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("Scope")) + customScopeAnnotations
  internal val bindingContainerAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("BindingContainer")) +
      customBindingContainerAnnotations

  internal val originAnnotations = setOf(Symbols.ClassIds.metroOrigin) + customOriginAnnotations

  internal val optionalBindingAnnotations =
    setOf(
      Symbols.FqNames.metroRuntimePackage.classIdOf("OptionalBinding"),
      // TODO can remove once OptionalDependency is removed
      Symbols.FqNames.metroRuntimePackage.classIdOf("OptionalDependency"),
    ) + customOptionalBindingAnnotations

  internal val bindsAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("Binds")) + customBindsAnnotations

  internal val providesAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("Provides")) + customProvidesAnnotations

  // Multibindings
  internal val intoSetAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("IntoSet")) + customIntoSetAnnotations
  internal val elementsIntoSetAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("ElementsIntoSet")) +
      customElementsIntoSetAnnotations
  internal val mapKeyAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("MapKey")) + customMapKeyAnnotations
  internal val intoMapAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("IntoMap")) + customIntoMapAnnotations
  internal val multibindsAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("Multibinds")) + customMultibindsAnnotations

  private val contributesToAnnotation =
    Symbols.FqNames.metroRuntimePackage.classIdOf("ContributesTo")
  private val contributesBindingAnnotation =
    Symbols.FqNames.metroRuntimePackage.classIdOf("ContributesBinding")
  private val contributesIntoSetAnnotation =
    Symbols.FqNames.metroRuntimePackage.classIdOf("ContributesIntoSet")
  private val contributesIntoMapAnnotation =
    Symbols.FqNames.metroRuntimePackage.classIdOf("ContributesIntoMap")

  internal val contributesToAnnotations =
    setOf(contributesToAnnotation) + customContributesToAnnotations
  internal val contributesBindingAnnotations =
    setOf(contributesBindingAnnotation) + customContributesBindingAnnotations
  internal val contributesIntoSetAnnotations =
    setOf(contributesIntoSetAnnotation) + customElementsIntoSetAnnotations
  internal val contributesIntoMapAnnotations =
    setOf(contributesIntoMapAnnotation) + customIntoMapAnnotations
  internal val graphExtensionAnnotations =
    setOf(Symbols.ClassIds.graphExtension) + customGraphExtensionAnnotations
  internal val graphExtensionFactoryAnnotations =
    setOf(Symbols.ClassIds.graphExtensionFactory) + customGraphExtensionFactoryAnnotations
  internal val allGraphExtensionAndFactoryAnnotations =
    graphExtensionAnnotations + graphExtensionFactoryAnnotations

  internal val allContributesAnnotations =
    contributesToAnnotations +
      contributesBindingAnnotations +
      contributesIntoSetAnnotations +
      contributesIntoMapAnnotations +
      customContributesIntoSetAnnotations

  /**
   * Repeatable annotations in compiled sources behave interestingly. They get an implicit
   * `Container` nested class that has an array value of the repeated annotations. For example:
   * `ContributesBinding.Container`
   *
   * Note that not all of these may actually be repeatable, but this doesn't need to resolve it for
   * sure and is just a general catch-all.
   */
  internal val allRepeatableContributesAnnotationsContainers =
    allContributesAnnotations.mapToSet { it.createNestedClassId(Symbols.Names.Container) }

  internal val allContributesAnnotationsWithContainers =
    allContributesAnnotations + allRepeatableContributesAnnotationsContainers

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
  internal val injectLikeAnnotations =
    if (contributesAsInject) {
      injectAnnotations +
        assistedInjectAnnotations +
        contributesBindingAnnotations +
        contributesIntoSetAnnotations +
        contributesIntoMapAnnotations
    } else {
      injectAnnotations + assistedInjectAnnotations
    }

  internal val providerTypes = setOf(Symbols.ClassIds.metroProvider) + customProviderClasses
  internal val lazyTypes = setOf(Symbols.ClassIds.Lazy) + customLazyClasses

  internal val includes = setOf(Symbols.ClassIds.metroIncludes)

  internal val allCustomAnnotations = buildSet {
    addAll(customLazyClasses)
    addAll(customProviderClasses)
    addAll(customAssistedAnnotations)
    addAll(customAssistedFactoryAnnotations)
    addAll(customAssistedInjectAnnotations)
    addAll(customBindsAnnotations)
    addAll(customContributesToAnnotations)
    addAll(customContributesBindingAnnotations)
    addAll(customContributesIntoSetAnnotations)
    addAll(customGraphExtensionAnnotations)
    addAll(customGraphExtensionFactoryAnnotations)
    addAll(customElementsIntoSetAnnotations)
    addAll(customGraphAnnotations)
    addAll(customGraphFactoryAnnotations)
    addAll(customInjectAnnotations)
    addAll(customIntoMapAnnotations)
    addAll(customIntoSetAnnotations)
    addAll(customMapKeyAnnotations)
    addAll(customMultibindsAnnotations)
    addAll(customProvidesAnnotations)
    addAll(customQualifierAnnotations)
    addAll(customScopeAnnotations)
    addAll(customBindingContainerAnnotations)
    addAll(customOriginAnnotations)
    addAll(customOptionalBindingAnnotations)
  }
}
