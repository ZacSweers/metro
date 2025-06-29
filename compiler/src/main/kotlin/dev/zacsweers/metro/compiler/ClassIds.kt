// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

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
  customContributesGraphExtensionAnnotations: Set<ClassId> = emptySet(),
  customContributesGraphExtensionFactoryAnnotations: Set<ClassId> = emptySet(),
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
        customContributesGraphExtensionAnnotations =
          options.customContributesGraphExtensionAnnotations,
        customContributesGraphExtensionFactoryAnnotations =
          options.customContributesGraphExtensionFactoryAnnotations,
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
  private val metroAssisted = Symbols.FqNames.metroRuntimePackage.classIdOf("Assisted")
  internal val assistedAnnotations = setOf(metroAssisted) + customAssistedAnnotations
  internal val metroAssistedFactory =
    Symbols.FqNames.metroRuntimePackage.classIdOf("AssistedFactory")
  internal val assistedFactoryAnnotations =
    setOf(metroAssistedFactory) + customAssistedFactoryAnnotations

  internal val injectAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("Inject")) +
      customInjectAnnotations +
      customAssistedInjectAnnotations

  internal val qualifierAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("Qualifier")) + customQualifierAnnotations
  internal val scopeAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("Scope")) + customScopeAnnotations
  internal val bindingContainerAnnotations =
    setOf(Symbols.FqNames.metroRuntimePackage.classIdOf("BindingContainer")) + customBindingContainerAnnotations

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
  private val contributesGraphExtensionAnnotation =
    Symbols.FqNames.metroRuntimePackage.classIdOf("ContributesGraphExtension")
  private val contributesGraphExtensionFactoryAnnotation =
    contributesGraphExtensionAnnotation.createNestedClassId(Symbols.Names.FactoryClass)

  internal val contributesToAnnotations =
    setOf(contributesToAnnotation) + customContributesToAnnotations
  internal val contributesBindingAnnotations =
    setOf(contributesBindingAnnotation) + customContributesBindingAnnotations
  internal val contributesIntoSetAnnotations =
    setOf(contributesIntoSetAnnotation) + customElementsIntoSetAnnotations
  internal val contributesIntoMapAnnotations =
    setOf(contributesIntoMapAnnotation) + customIntoMapAnnotations
  internal val contributesGraphExtensionAnnotations =
    setOf(contributesGraphExtensionAnnotation) + customContributesGraphExtensionAnnotations
  internal val contributesGraphExtensionFactoryAnnotations =
    setOf(contributesGraphExtensionFactoryAnnotation) +
      customContributesGraphExtensionFactoryAnnotations
  internal val contributesToLikeAnnotations =
    contributesToAnnotations + contributesGraphExtensionFactoryAnnotations
  internal val allContributesAnnotations =
    contributesToAnnotations +
      contributesBindingAnnotations +
      contributesIntoSetAnnotations +
      contributesIntoMapAnnotations +
      customContributesIntoSetAnnotations +
      contributesGraphExtensionFactoryAnnotations

  internal val graphLikeAnnotations =
    dependencyGraphAnnotations + contributesGraphExtensionAnnotations
  internal val graphFactoryLikeAnnotations =
    dependencyGraphFactoryAnnotations + contributesGraphExtensionFactoryAnnotations

  internal val providerTypes = setOf(Symbols.ClassIds.metroProvider) + customProviderClasses
  internal val lazyTypes = setOf(Symbols.ClassIds.Lazy) + customLazyClasses

  internal val includes = setOf(Symbols.ClassIds.metroIncludes)
  internal val extends = setOf(Symbols.ClassIds.metroExtends)
}
