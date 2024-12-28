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
package dev.zacsweers.lattice.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.lattice.compiler.LatticeClassIds
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId

@Poko
internal class LatticeIrAnnotations(
  val isDependencyGraph: Boolean,
  val isDependencyGraphFactory: Boolean,
  val isInject: Boolean,
  val isAssistedInject: Boolean,
  val isProvides: Boolean,
  val isBinds: Boolean,
  val isBindsInstance: Boolean,
  val isIntoSet: Boolean,
  val isElementsIntoSet: Boolean,
  val isIntoMap: Boolean,
  val isMultibinds: Boolean,
  val assisted: IrAnnotation?,
  val scope: IrAnnotation?,
  val qualifier: IrAnnotation?,
  val mapKeys: Set<IrAnnotation>,
) {
  val isAssisted
    get() = assisted != null

  val isScoped
    get() = scope != null

  val isQualified
    get() = qualifier != null

  fun mergeWith(other: LatticeIrAnnotations): LatticeIrAnnotations =
    LatticeIrAnnotations(
      isDependencyGraph = isDependencyGraph || other.isDependencyGraph,
      isDependencyGraphFactory = isDependencyGraphFactory || other.isDependencyGraphFactory,
      isInject = isInject || other.isInject,
      isAssistedInject = isAssistedInject || other.isAssistedInject,
      isProvides = isProvides || other.isProvides,
      isBinds = isBinds || other.isBinds,
      isBindsInstance = isBindsInstance || other.isBindsInstance,
      isIntoSet = isIntoSet || other.isIntoSet,
      isElementsIntoSet = isElementsIntoSet || other.isElementsIntoSet,
      isIntoMap = isIntoMap || other.isIntoMap,
      isMultibinds = isMultibinds || other.isMultibinds,
      assisted = assisted ?: other.assisted,
      scope = scope ?: other.scope,
      qualifier = qualifier ?: other.qualifier,
      mapKeys = mapKeys + other.mapKeys,
    )
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrAnnotationContainer.latticeAnnotations(ids: LatticeClassIds): LatticeIrAnnotations =
  latticeAnnotations(ids, null)

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrAnnotationContainer.latticeAnnotations(
  ids: LatticeClassIds,
  callingContainer: IrAnnotationContainer?,
): LatticeIrAnnotations {
  var isDependencyGraph = false
  var isDependencyGraphFactory = false
  var isInject = false
  var isAssistedInject = false
  var isProvides = false
  var isBinds = false
  var isBindsInstance = false
  var isIntoSet = false
  var isElementsIntoSet = false
  var isIntoMap = false
  var isMultibinds = false
  var assisted: IrAnnotation? = null
  var scope: IrAnnotation? = null
  var qualifier: IrAnnotation? = null
  var mapKeys = mutableSetOf<IrAnnotation>()

  for (it in annotations) {
    val annotationClass = it.type.classOrNull?.owner ?: continue
    val classId = annotationClass.classId ?: continue

    when (this) {
      is IrValueParameter -> {
        // Only BindsInstance and Assisted go here
        if (classId in ids.bindsInstanceAnnotations) {
          isBindsInstance = true
          continue
        } else if (classId in ids.assistedAnnotations) {
          assisted = expectNullAndSet("assisted", assisted, it.asIrAnnotation())
          continue
        }
      }

      is IrFunction,
      is IrProperty -> {
        // Binds, Provides
        if (classId in ids.bindsAnnotations) {
          isBinds = true
          continue
        } else if (classId in ids.providesAnnotations) {
          isProvides = true
          continue
        } else if (classId in ids.intoSetAnnotations) {
          isIntoSet = true
          continue
        } else if (classId in ids.elementsIntoSetAnnotations) {
          isElementsIntoSet = true
          continue
        } else if (classId in ids.intoMapAnnotations) {
          isIntoMap = true
          continue
        } else if (classId in ids.multibindsAnnotations) {
          isMultibinds = true
          continue
        }
      }

      is IrClass -> {
        // AssistedFactory, DependencyGraph, DependencyGraph.Factory
        if (classId in ids.assistedFactoryAnnotations) {
          isBinds = true
          continue
        } else if (classId in ids.dependencyGraphAnnotations) {
          isDependencyGraph = true
          continue
        } else if (classId in ids.dependencyGraphFactoryAnnotations) {
          isDependencyGraphFactory = true
          continue
        }
      }
    }

    // Everything below applies to multiple targets

    if (classId in ids.injectAnnotations) {
      isInject = true
      continue
    } else if (classId in ids.assistedInjectAnnotations) {
      isAssistedInject = true
      continue
    }

    if (annotationClass.isAnnotatedWithAny(ids.scopeAnnotations)) {
      scope = expectNullAndSet("scope", scope, it.asIrAnnotation())
      continue
    } else if (annotationClass.isAnnotatedWithAny(ids.qualifierAnnotations)) {
      qualifier = expectNullAndSet("qualifier", qualifier, it.asIrAnnotation())
      continue
    } else if (annotationClass.isAnnotatedWithAny(ids.mapKeyAnnotations)) {
      mapKeys += it.asIrAnnotation()
      continue
    }
  }

  val annotations =
    LatticeIrAnnotations(
      isDependencyGraph = isDependencyGraph,
      isDependencyGraphFactory = isDependencyGraphFactory,
      isInject = isInject,
      isAssistedInject = isAssistedInject,
      isProvides = isProvides,
      isBinds = isBinds,
      isBindsInstance = isBindsInstance,
      isIntoSet = isIntoSet,
      isElementsIntoSet = isElementsIntoSet,
      isIntoMap = isIntoMap,
      isMultibinds = isMultibinds,
      assisted = assisted,
      scope = scope,
      qualifier = qualifier,
      mapKeys = mapKeys,
    )

  val thisContainer = this

  return sequence {
      yield(annotations)

      // You can fit so many annotations in properties
      if (thisContainer is IrProperty) {
        // Retrieve annotations from this property's various accessors
        getter?.let { getter ->
          if (getter != callingContainer) {
            yield(getter.latticeAnnotations(ids, callingContainer = thisContainer))
          }
        }
        setter?.let { setter ->
          if (setter != callingContainer) {
            yield(setter.latticeAnnotations(ids, callingContainer = thisContainer))
          }
        }
        backingField?.let { field ->
          if (field != callingContainer) {
            yield(field.latticeAnnotations(ids, callingContainer = thisContainer))
          }
        }
      } else if (thisContainer is IrSimpleFunction) {
        correspondingPropertySymbol?.owner?.let { property ->
          if (property != callingContainer) {
            val propertyAnnotations =
              property.latticeAnnotations(ids, callingContainer = thisContainer)
            yield(propertyAnnotations)
          }
        }
      }
    }
    .reduce(LatticeIrAnnotations::mergeWith)
}

internal fun <T> expectNullAndSet(type: String, current: T?, value: T): T {
  check(current == null) { "Multiple $type annotations found! Found $current and $value." }
  return value
}
