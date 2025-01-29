/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.ir.util.kotlinPackageFqn
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

public class ClassIds(
  customAssistedAnnotations: Set<ClassId> = emptySet(),
  customAssistedFactoryAnnotations: Set<ClassId> = emptySet(),
  customAssistedInjectAnnotations: Set<ClassId> = emptySet(),
  customBindsAnnotations: Set<ClassId> = emptySet(),
  customContributesToAnnotations: Set<ClassId> = emptySet(),
  customContributesBindingAnnotations: Set<ClassId> = emptySet(),
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
) {
  private fun FqName.classIdOf(simpleName: String): ClassId {
    return classIdOf(Name.identifier(simpleName))
  }

  private fun FqName.classIdOf(simpleName: Name): ClassId {
    return ClassId(this, simpleName)
  }

  // Graphs
  private val dependencyGraphAnnotation =
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

  internal val originAnnotation = Symbols.FqNames.metroRuntimeInternalPackage.classIdOf("Origin")
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
  internal val contributesIntoSetAnnotations = setOf(contributesIntoSetAnnotation) // TODO custom
  internal val contributesIntoMapAnnotations = setOf(contributesIntoMapAnnotation) // TODO custom
  internal val allContributesAnnotations =
    contributesToAnnotations +
      contributesBindingAnnotations +
      contributesIntoSetAnnotations +
      contributesIntoMapAnnotations

  internal val providerTypes = setOf(Symbols.ClassIds.metroProvider)
  internal val lazyTypes = setOf(kotlinPackageFqn.classIdOf("Lazy"))
}
