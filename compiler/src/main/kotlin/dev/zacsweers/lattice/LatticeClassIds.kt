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
package dev.zacsweers.lattice

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class LatticeClassIds(
  customInjectAnnotations: Set<ClassId> = emptySet(),
  customProvidesAnnotations: Set<ClassId> = emptySet(),
  customBindsAnnotations: Set<ClassId> = emptySet(),
  customObjectGraphAnnotations: Set<ClassId> = emptySet(),
  customScopeAnnotations: Set<ClassId> = emptySet(),
  customQualifierAnnotations: Set<ClassId> = emptySet(),
  customBindsInstanceAnnotations: Set<ClassId> = emptySet(),
  customAssistedAnnotations: Set<ClassId> = emptySet(),
  customAssistedInjectAnnotations: Set<ClassId> = emptySet(),
  customAssistedFactoryAnnotations: Set<ClassId> = emptySet(),
  customIntoSetAnnotations: Set<ClassId> = emptySet(),
  customElementsIntoSetAnnotations: Set<ClassId> = emptySet(),
  customMapKeyAnnotations: Set<ClassId> = emptySet(),
  customClassKeyAnnotations: Set<ClassId> = emptySet(),
  customIntKeyAnnotations: Set<ClassId> = emptySet(),
  customLongKeyAnnotations: Set<ClassId> = emptySet(),
  customStringKeyAnnotations: Set<ClassId> = emptySet(),
  customLazyClassKeyAnnotations: Set<ClassId> = emptySet(),
  customIntoMapAnnotations: Set<ClassId> = emptySet(),
  customMultibindsAnnotations: Set<ClassId> = emptySet(),
) {
  companion object {
    val STDLIB_PACKAGE = FqName("kotlin")
    val LATTICE_RUNTIME_PACKAGE = FqName("dev.zacsweers.lattice")
    val LATTICE_ANNOTATIONS_PACKAGE = FqName("dev.zacsweers.lattice.annotations")
    val LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE =
      FqName("dev.zacsweers.lattice.annotations.multibindings")
  }

  fun FqName.classIdOf(simpleName: String): ClassId {
    return classIdOf(Name.identifier(simpleName))
  }

  fun FqName.classIdOf(simpleName: Name): ClassId {
    return ClassId(this, simpleName)
  }

  fun FqName.classIdOf(relativeClassName: FqName): ClassId {
    return ClassId(this, relativeClassName, isLocal = false)
  }

  // Graphs
  private val objectGraphAnnotation = LATTICE_ANNOTATIONS_PACKAGE.classIdOf("ObjectGraph")
  val objectGraphAnnotations = setOf(objectGraphAnnotation) + customObjectGraphAnnotations
  val objectGraphFactoryAnnotations =
    setOf(objectGraphAnnotation.createNestedClassId(Name.identifier("Factory")))

  // Assisted inject
  val assistedInjectAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("AssistedInject")) + customAssistedInjectAnnotations
  val latticeAssisted = LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Assisted")
  val assistedAnnotations = setOf(latticeAssisted) + customAssistedAnnotations
  val latticeAssistedFactory = LATTICE_ANNOTATIONS_PACKAGE.classIdOf("AssistedFactory")
  val assistedFactoryAnnotations = setOf(latticeAssistedFactory) + customAssistedFactoryAnnotations

  val injectAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Inject")) +
      customInjectAnnotations +
      assistedInjectAnnotations

  val qualifierAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Qualifier")) + customQualifierAnnotations
  val scopeAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Scope")) + customScopeAnnotations

  val bindsAnnotations = customBindsAnnotations
  val providesAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Provides")) +
      customProvidesAnnotations +
      bindsAnnotations

  val bindsInstanceAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("BindsInstance")) + customBindsInstanceAnnotations

  // Multibindings
  val intoSetAnnotations =
    setOf(LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE.classIdOf("IntoSet")) + customIntoSetAnnotations
  val elementsIntoSetAnnotations =
    setOf(LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE.classIdOf("ElementsIntoSet")) +
      customElementsIntoSetAnnotations
  val mapKeyAnnotations =
    setOf(LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE.classIdOf("MapKey")) + customMapKeyAnnotations
  val classKeyAnnotations =
    setOf(LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE.classIdOf("ClassKey")) +
      customClassKeyAnnotations
  val intKeyAnnotations =
    setOf(LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE.classIdOf("IntKey")) + customIntKeyAnnotations
  val longKeyAnnotations =
    setOf(LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE.classIdOf("LongKey")) + customLongKeyAnnotations
  val stringKeyAnnotations =
    setOf(LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE.classIdOf("StringKey")) +
      customStringKeyAnnotations
  val lazyClassKeyAnnotations =
    setOf(LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE.classIdOf("LazyClassKey")) +
      customLazyClassKeyAnnotations
  val intoMapAnnotations =
    setOf(LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE.classIdOf("IntoMap")) + customIntoMapAnnotations
  val multibindsAnnotations =
    setOf(LATTICE_MULTIBINDING_ANNOTATIONS_PACKAGE.classIdOf("Multibinds")) +
      customMultibindsAnnotations

  val providerTypes = setOf(LATTICE_RUNTIME_PACKAGE.classIdOf("Provider"))
  val lazyTypes = setOf(STDLIB_PACKAGE.classIdOf("Lazy"))
}
