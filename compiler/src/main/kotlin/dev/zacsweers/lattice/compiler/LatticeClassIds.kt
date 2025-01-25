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
package dev.zacsweers.lattice.compiler

import org.jetbrains.kotlin.ir.util.kotlinPackageFqn
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class LatticeClassIds(
  customAssistedAnnotations: Set<ClassId> = emptySet(),
  customAssistedFactoryAnnotations: Set<ClassId> = emptySet(),
  customAssistedInjectAnnotations: Set<ClassId> = emptySet(),
  customBindsAnnotations: Set<ClassId> = emptySet(),
  customBindsInstanceAnnotations: Set<ClassId> = emptySet(),
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
    LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("DependencyGraph")
  val dependencyGraphAnnotations =
    setOf(dependencyGraphAnnotation) + customGraphAnnotations
  val dependencyGraphFactoryAnnotations =
    setOf(dependencyGraphAnnotation.createNestedClassId(Name.identifier("Factory"))) + customGraphFactoryAnnotations

  // Assisted inject
  private val latticeAssisted = LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("Assisted")
  val assistedAnnotations = setOf(latticeAssisted) + customAssistedAnnotations
  val latticeAssistedFactory =
    LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("AssistedFactory")
  val assistedFactoryAnnotations = setOf(latticeAssistedFactory) + customAssistedFactoryAnnotations

  val injectAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("Inject")) +
      customInjectAnnotations +
      customAssistedInjectAnnotations

  val qualifierAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("Qualifier")) +
      customQualifierAnnotations
  val scopeAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("Scope")) + customScopeAnnotations

  val bindsAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("Binds")) + customBindsAnnotations

  val providesAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("Provides")) +
      customProvidesAnnotations

  val bindsInstanceAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("BindsInstance")) +
      customBindsInstanceAnnotations

  // Multibindings
  val intoSetAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("IntoSet")) +
      customIntoSetAnnotations
  val elementsIntoSetAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("ElementsIntoSet")) +
      customElementsIntoSetAnnotations
  val mapKeyAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("MapKey")) +
      customMapKeyAnnotations
  val intoMapAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("IntoMap")) +
      customIntoMapAnnotations
  val multibindsAnnotations =
    setOf(LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("Multibinds")) +
      customMultibindsAnnotations

  val originAnnotation = LatticeSymbols.FqNames.latticeRuntimeInternalPackage.classIdOf("Origin")
  private val contributesToAnnotation =
    LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("ContributesTo")
  private val contributesBindingAnnotation =
    LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("ContributesBinding")
  private val contributesIntoSetAnnotation =
    LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("ContributesIntoSet")
  private val contributesIntoMapAnnotation =
    LatticeSymbols.FqNames.latticeRuntimePackage.classIdOf("ContributesIntoMap")

  val contributesToAnnotations = setOf(contributesToAnnotation) + customContributesToAnnotations
  val contributesBindingAnnotations = setOf(contributesBindingAnnotation) + customContributesBindingAnnotations
  val contributesIntoSetAnnotations = setOf(contributesIntoSetAnnotation) // TODO custom
  val contributesIntoMapAnnotations = setOf(contributesIntoMapAnnotation) // TODO custom
  val allContributesAnnotations =
    contributesToAnnotations +
      contributesBindingAnnotations +
      contributesIntoSetAnnotations +
      contributesIntoMapAnnotations

  val providerTypes = setOf(LatticeSymbols.ClassIds.latticeProvider)
  val lazyTypes = setOf(kotlinPackageFqn.classIdOf("Lazy"))
}
