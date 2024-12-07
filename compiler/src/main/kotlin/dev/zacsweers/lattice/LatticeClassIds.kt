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
  customComponentAnnotations: Set<ClassId> = emptySet(),
  customScopeAnnotations: Set<ClassId> = emptySet(),
  customQualifierAnnotations: Set<ClassId> = emptySet(),
  customBindsInstanceAnnotations: Set<ClassId> = emptySet(),
  customAssistedAnnotations: Set<ClassId> = emptySet(),
  customAssistedInjectAnnotations: Set<ClassId> = emptySet(),
  customAssistedFactoryAnnotations: Set<ClassId> = emptySet(),
) {
  companion object {
    val STDLIB_PACKAGE = FqName("kotlin")
    val LATTICE_RUNTIME_PACKAGE = FqName("dev.zacsweers.lattice")
    val LATTICE_ANNOTATIONS_PACKAGE = FqName("dev.zacsweers.lattice.annotations")
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

  private val componentAnnotation = LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Component")
  val componentAnnotations = setOf(componentAnnotation) + customComponentAnnotations
  val componentFactoryAnnotations =
    setOf(componentAnnotation.createNestedClassId(Name.identifier("Factory")))
  val assistedAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Assisted")) + customAssistedAnnotations
  val injectAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Inject")) +
      customInjectAnnotations +
      assistedAnnotations
  val qualifierAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Qualifier")) + customQualifierAnnotations
  val scopeAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Scope")) + customScopeAnnotations
  val providesAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("Provides")) + customProvidesAnnotations
  val bindsInstanceAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("BindsInstance")) + customBindsInstanceAnnotations
  val assistedInjectAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("AssistedInject")) + customAssistedInjectAnnotations
  val assistedFactoryAnnotations =
    setOf(LATTICE_ANNOTATIONS_PACKAGE.classIdOf("AssistedFactory")) +
      customAssistedFactoryAnnotations
  val providerTypes = setOf(LATTICE_RUNTIME_PACKAGE.classIdOf("Provider"))
  val lazyTypes = setOf(STDLIB_PACKAGE.classIdOf("Lazy"))
}
