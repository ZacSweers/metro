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

import dev.drewhamilton.poko.Poko
import dev.zacsweers.lattice.compiler.fir.LatticeFirAnnotation
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.ir.IrAnnotation
import dev.zacsweers.lattice.compiler.ir.asIrAnnotation
import dev.zacsweers.lattice.compiler.ir.isAnnotatedWithAny
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId

@Poko
internal class LatticeAnnotations<T>(
  val isDependencyGraph: Boolean,
  val isDependencyGraphFactory: Boolean,
  val isInject: Boolean,
  val isProvides: Boolean,
  val isBinds: Boolean,
  val isBindsInstance: Boolean,
  val isIntoSet: Boolean,
  val isElementsIntoSet: Boolean,
  val isIntoMap: Boolean,
  val isMultibinds: Boolean,
  val isAssistedFactory: Boolean,
  val isComposable: Boolean,
  val assisted: T?,
  val scope: T?,
  val qualifier: T?,
  val mapKeys: Set<T>,
) {
  val isAssisted
    get() = assisted != null

  val isScoped
    get() = scope != null

  val isQualified
    get() = qualifier != null

  val isIntoMultibinding
    get() = isIntoSet || isElementsIntoSet || isIntoMap || mapKeys.isNotEmpty()

  fun copy(
    isDependencyGraph: Boolean = this.isDependencyGraph,
    isDependencyGraphFactory: Boolean = this.isDependencyGraphFactory,
    isInject: Boolean = this.isInject,
    isProvides: Boolean = this.isProvides,
    isBinds: Boolean = this.isBinds,
    isBindsInstance: Boolean = this.isBindsInstance,
    isIntoSet: Boolean = this.isIntoSet,
    isElementsIntoSet: Boolean = this.isElementsIntoSet,
    isIntoMap: Boolean = this.isIntoMap,
    isMultibinds: Boolean = this.isMultibinds,
    isAssistedFactory: Boolean = this.isAssistedFactory,
    isComposable: Boolean = this.isComposable,
    assisted: T? = this.assisted,
    scope: T? = this.scope,
    qualifier: T? = this.qualifier,
    mapKeys: Set<T> = this.mapKeys,
  ): LatticeAnnotations<T> {
    return LatticeAnnotations(
      isDependencyGraph,
      isDependencyGraphFactory,
      isInject,
      isProvides,
      isBinds,
      isBindsInstance,
      isIntoSet,
      isElementsIntoSet,
      isIntoMap,
      isMultibinds,
      isAssistedFactory,
      isComposable,
      assisted,
      scope,
      qualifier,
      mapKeys,
    )
  }

  fun mergeWith(other: LatticeAnnotations<T>): LatticeAnnotations<T> =
    copy(
      isDependencyGraph = isDependencyGraph || other.isDependencyGraph,
      isDependencyGraphFactory = isDependencyGraphFactory || other.isDependencyGraphFactory,
      isInject = isInject || other.isInject,
      isProvides = isProvides || other.isProvides,
      isBinds = isBinds || other.isBinds,
      isBindsInstance = isBindsInstance || other.isBindsInstance,
      isIntoSet = isIntoSet || other.isIntoSet,
      isElementsIntoSet = isElementsIntoSet || other.isElementsIntoSet,
      isIntoMap = isIntoMap || other.isIntoMap,
      isMultibinds = isMultibinds || other.isMultibinds,
      isAssistedFactory = isAssistedFactory || other.isAssistedFactory,
      assisted = assisted ?: other.assisted,
      scope = scope ?: other.scope,
      qualifier = qualifier ?: other.qualifier,
      mapKeys = mapKeys + other.mapKeys,
    )
}

internal fun IrAnnotationContainer.latticeAnnotations(
  ids: LatticeClassIds
): LatticeAnnotations<IrAnnotation> = latticeAnnotations(ids, null)

private fun IrAnnotationContainer.latticeAnnotations(
  ids: LatticeClassIds,
  callingContainer: IrAnnotationContainer?,
): LatticeAnnotations<IrAnnotation> {
  var isDependencyGraph = false
  var isDependencyGraphFactory = false
  var isInject = false
  var isProvides = false
  var isBinds = false
  var isBindsInstance = false
  var isIntoSet = false
  var isElementsIntoSet = false
  var isIntoMap = false
  var isMultibinds = false
  var isAssistedFactory = false
  var isComposable = false
  var assisted: IrAnnotation? = null
  var scope: IrAnnotation? = null
  var qualifier: IrAnnotation? = null
  val mapKeys = mutableSetOf<IrAnnotation>()

  for (annotation in annotations) {
    val annotationClass = annotation.type.classOrNull?.owner ?: continue
    val classId = annotationClass.classId ?: continue

    when (this) {
      is IrValueParameter -> {
        // Only BindsInstance and Assisted go here
        when (classId) {
          in ids.bindsInstanceAnnotations -> {
            isBindsInstance = true
            continue
          }
          in ids.assistedAnnotations -> {
            assisted = expectNullAndSet("assisted", assisted, annotation.asIrAnnotation())
            continue
          }
        }
      }

      is IrFunction,
      is IrProperty -> {
        // Binds, Provides
        when (classId) {
          in ids.bindsAnnotations -> {
            isBinds = true
            continue
          }
          in ids.providesAnnotations -> {
            isProvides = true
            continue
          }
          in ids.intoSetAnnotations -> {
            isIntoSet = true
            continue
          }
          in ids.elementsIntoSetAnnotations -> {
            isElementsIntoSet = true
            continue
          }
          in ids.intoMapAnnotations -> {
            isIntoMap = true
            continue
          }
          in ids.multibindsAnnotations -> {
            isMultibinds = true
            continue
          }
          LatticeSymbols.ClassIds.composable -> {
            isComposable = true
            continue
          }
        }
      }

      is IrClass -> {
        // AssistedFactory, DependencyGraph, DependencyGraph.Factory
        when (classId) {
          in ids.assistedFactoryAnnotations -> {
            isAssistedFactory = true
            continue
          }
          in ids.dependencyGraphAnnotations -> {
            isDependencyGraph = true
            continue
          }
          in ids.dependencyGraphFactoryAnnotations -> {
            isDependencyGraphFactory = true
            continue
          }
        }
      }
    }

    // Everything below applies to multiple targets

    if (classId in ids.injectAnnotations) {
      isInject = true
      continue
    }

    if (annotationClass.isAnnotatedWithAny(ids.scopeAnnotations)) {
      scope = expectNullAndSet("scope", scope, annotation.asIrAnnotation())
      continue
    } else if (annotationClass.isAnnotatedWithAny(ids.qualifierAnnotations)) {
      qualifier = expectNullAndSet("qualifier", qualifier, annotation.asIrAnnotation())
      continue
    } else if (annotationClass.isAnnotatedWithAny(ids.mapKeyAnnotations)) {
      mapKeys += annotation.asIrAnnotation()
      continue
    }
  }

  val annotations =
    LatticeAnnotations(
      isDependencyGraph = isDependencyGraph,
      isDependencyGraphFactory = isDependencyGraphFactory,
      isInject = isInject,
      isProvides = isProvides,
      isBinds = isBinds,
      isBindsInstance = isBindsInstance,
      isIntoSet = isIntoSet,
      isElementsIntoSet = isElementsIntoSet,
      isIntoMap = isIntoMap,
      isMultibinds = isMultibinds,
      isAssistedFactory = isAssistedFactory,
      isComposable = isComposable,
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
      } else if (thisContainer is IrField) {
        correspondingPropertySymbol?.owner?.let { property ->
          if (property != callingContainer) {
            val propertyAnnotations =
              property.latticeAnnotations(ids, callingContainer = thisContainer)
            yield(propertyAnnotations)
          }
        }
      }
    }
    .reduce(LatticeAnnotations<IrAnnotation>::mergeWith)
}

internal fun FirBasedSymbol<*>.latticeAnnotations(
  session: FirSession
): LatticeAnnotations<LatticeFirAnnotation> {
  return latticeAnnotations(session, null)
}

private fun FirBasedSymbol<*>.latticeAnnotations(
  session: FirSession,
  callingContainer: FirBasedSymbol<*>?,
): LatticeAnnotations<LatticeFirAnnotation> {
  val ids = session.latticeClassIds
  var isDependencyGraph = false
  var isDependencyGraphFactory = false
  var isInject = false
  var isProvides = false
  var isBinds = false
  var isBindsInstance = false
  var isIntoSet = false
  var isElementsIntoSet = false
  var isIntoMap = false
  var isMultibinds = false
  var isAssistedFactory = false
  var isComposable = false
  var assisted: LatticeFirAnnotation? = null
  var scope: LatticeFirAnnotation? = null
  var qualifier: LatticeFirAnnotation? = null
  val mapKeys = mutableSetOf<LatticeFirAnnotation>()

  for (annotation in resolvedAnnotationsWithArguments) {
    if (annotation !is FirAnnotationCall) continue
    val annotationType = annotation.resolvedType as? ConeClassLikeType ?: continue
    val annotationClass = annotationType.toClassSymbol(session) ?: continue
    val classId = annotationClass.classId

    when (this) {
      is FirValueParameterSymbol -> {
        // Only BindsInstance and Assisted go here
        when (classId) {
          in ids.bindsInstanceAnnotations -> {
            isBindsInstance = true
            continue
          }
          in ids.assistedAnnotations -> {
            assisted = expectNullAndSet("assisted", assisted, LatticeFirAnnotation(annotation))
            continue
          }
        }
      }

      is FirNamedFunctionSymbol,
      is FirPropertySymbol -> {
        // Binds, Provides
        when (classId) {
          in ids.bindsAnnotations -> {
            isBinds = true
            continue
          }
          in ids.providesAnnotations -> {
            isProvides = true
            continue
          }
          in ids.intoSetAnnotations -> {
            isIntoSet = true
            continue
          }
          in ids.elementsIntoSetAnnotations -> {
            isElementsIntoSet = true
            continue
          }
          in ids.intoMapAnnotations -> {
            isIntoMap = true
            continue
          }
          in ids.multibindsAnnotations -> {
            isMultibinds = true
            continue
          }
          LatticeSymbols.ClassIds.composable -> {
            isComposable = true
            continue
          }
        }
      }

      is FirClassSymbol<*> -> {
        // AssistedFactory, DependencyGraph, DependencyGraph.Factory
        if (classId in ids.assistedFactoryAnnotations) {
          isAssistedFactory = true
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
    }

    if (annotationClass.isAnnotatedWithAny(session, ids.scopeAnnotations)) {
      scope = expectNullAndSet("scope", scope, LatticeFirAnnotation(annotation))
      continue
    } else if (annotationClass.isAnnotatedWithAny(session, ids.qualifierAnnotations)) {
      qualifier = expectNullAndSet("qualifier", qualifier, LatticeFirAnnotation(annotation))
      continue
    } else if (annotationClass.isAnnotatedWithAny(session, ids.mapKeyAnnotations)) {
      mapKeys += LatticeFirAnnotation(annotation)
      continue
    }
  }

  val annotations =
    LatticeAnnotations(
      isDependencyGraph = isDependencyGraph,
      isDependencyGraphFactory = isDependencyGraphFactory,
      isInject = isInject,
      isProvides = isProvides,
      isBinds = isBinds,
      isBindsInstance = isBindsInstance,
      isIntoSet = isIntoSet,
      isElementsIntoSet = isElementsIntoSet,
      isIntoMap = isIntoMap,
      isMultibinds = isMultibinds,
      isAssistedFactory = isAssistedFactory,
      isComposable = isComposable,
      assisted = assisted,
      scope = scope,
      qualifier = qualifier,
      mapKeys = mapKeys,
    )

  val thisContainer = this

  return sequence {
      yield(annotations)

      // You can fit so many annotations in properties
      if (thisContainer is FirPropertySymbol) {
        // Retrieve annotations from this property's various accessors
        getterSymbol?.let { getter ->
          if (getter != callingContainer) {
            yield(getter.latticeAnnotations(session, callingContainer = thisContainer))
          }
        }
        setterSymbol?.let { setter ->
          if (setter != callingContainer) {
            yield(setter.latticeAnnotations(session, callingContainer = thisContainer))
          }
        }
        backingFieldSymbol?.let { field ->
          if (field != callingContainer) {
            yield(field.latticeAnnotations(session, callingContainer = thisContainer))
          }
        }
      } else if (thisContainer is FirNamedFunctionSymbol) {
        // TODO?
        //  correspondingPropertySymbol?.owner?.let { property ->
        //    if (property != callingContainer) {
        //      val propertyAnnotations =
        //        property.latticeAnnotations(ids, callingContainer = thisContainer)
        //      yield(propertyAnnotations)
        //    }
        //  }
      }
    }
    .reduce(LatticeAnnotations<LatticeFirAnnotation>::mergeWith)
}

internal fun <T> expectNullAndSet(type: String, current: T?, value: T): T {
  check(current == null) { "Multiple $type annotations found! Found $current and $value." }
  return value
}
