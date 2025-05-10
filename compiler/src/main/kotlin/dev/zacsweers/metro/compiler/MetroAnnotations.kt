// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.asIrAnnotation
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId

@Poko
internal class MetroAnnotations<T>(
  val isDependencyGraph: Boolean,
  val isDependencyGraphFactory: Boolean,
  val isInject: Boolean,
  val isProvides: Boolean,
  val isBinds: Boolean,
  val isBindsInstance: Boolean,
  val isIntoSet: Boolean,
  val isElementsIntoSet: Boolean,
  val isIntoMap: Boolean,
  val isAssistedFactory: Boolean,
  val isComposable: Boolean,
  val multibinds: T?,
  val assisted: T?,
  val scope: T?,
  val qualifier: T?,
  val mapKeys: Set<T>,
  // An IrAnnotation or FirAnnotation
  // TODO the lack of a type here is unfortunate
  val symbol: Any?,
) {
  val isMultibinds: Boolean
    get() = multibinds != null

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
    isAssistedFactory: Boolean = this.isAssistedFactory,
    isComposable: Boolean = this.isComposable,
    multibinds: T? = this.multibinds,
    assisted: T? = this.assisted,
    scope: T? = this.scope,
    qualifier: T? = this.qualifier,
    mapKeys: Set<T> = this.mapKeys,
  ): MetroAnnotations<T> {
    return MetroAnnotations(
      isDependencyGraph,
      isDependencyGraphFactory,
      isInject,
      isProvides,
      isBinds,
      isBindsInstance,
      isIntoSet,
      isElementsIntoSet,
      isIntoMap,
      isAssistedFactory,
      isComposable,
      multibinds,
      assisted,
      scope,
      qualifier,
      mapKeys,
      symbol
    )
  }

  fun mergeWith(other: MetroAnnotations<T>): MetroAnnotations<T> =
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
      isAssistedFactory = isAssistedFactory || other.isAssistedFactory,
      multibinds = multibinds ?: other.multibinds,
      assisted = assisted ?: other.assisted,
      scope = scope ?: other.scope,
      qualifier = qualifier ?: other.qualifier,
      mapKeys = mapKeys + other.mapKeys,
    )

  companion object {
    private val NONE =
      MetroAnnotations<Any>(
        isDependencyGraph = false,
        isDependencyGraphFactory = false,
        isInject = false,
        isProvides = false,
        isBinds = false,
        isBindsInstance = false,
        isIntoSet = false,
        isElementsIntoSet = false,
        isIntoMap = false,
        isAssistedFactory = false,
        isComposable = false,
        multibinds = null,
        assisted = false,
        scope = null,
        qualifier = null,
        mapKeys = emptySet(),
        symbol = null,
      )

    @Suppress("UNCHECKED_CAST") fun <T> none(): MetroAnnotations<T> = NONE as MetroAnnotations<T>
  }
}

internal fun IrAnnotationContainer.metroAnnotations(ids: ClassIds): MetroAnnotations<IrAnnotation> =
  metroAnnotations(ids, null)

private fun IrAnnotationContainer.metroAnnotations(
  ids: ClassIds,
  callingContainer: IrAnnotationContainer?,
): MetroAnnotations<IrAnnotation> {
  var isDependencyGraph = false
  var isDependencyGraphFactory = false
  var isInject = false
  var isProvides = false
  var isBinds = false
  var isBindsInstance = false
  var isIntoSet = false
  var isElementsIntoSet = false
  var isIntoMap = false
  var isAssistedFactory = false
  var isComposable = false
  var multibinds: IrAnnotation? = null
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
          in ids.providesAnnotations -> {
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
            multibinds = expectNullAndSet("multibindings", multibinds, annotation.asIrAnnotation())
            continue
          }
          Symbols.ClassIds.Composable -> {
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
    MetroAnnotations(
      isDependencyGraph = isDependencyGraph,
      isDependencyGraphFactory = isDependencyGraphFactory,
      isInject = isInject,
      isProvides = isProvides,
      isBinds = isBinds,
      isBindsInstance = isBindsInstance,
      isIntoSet = isIntoSet,
      isElementsIntoSet = isElementsIntoSet,
      isIntoMap = isIntoMap,
      isAssistedFactory = isAssistedFactory,
      isComposable = isComposable,
      multibinds = multibinds,
      assisted = assisted,
      scope = scope,
      qualifier = qualifier,
      mapKeys = mapKeys,
      symbol = (this as? IrDeclaration)?.symbol,
    )

  val thisContainer = this

  return sequence {
      yield(annotations)

      // You can fit so many annotations in properties
      if (thisContainer is IrProperty) {
        // Retrieve annotations from this property's various accessors
        getter?.let { getter ->
          if (getter != callingContainer) {
            yield(getter.metroAnnotations(ids, callingContainer = thisContainer))
          }
        }
        setter?.let { setter ->
          if (setter != callingContainer) {
            yield(setter.metroAnnotations(ids, callingContainer = thisContainer))
          }
        }
        backingField?.let { field ->
          if (field != callingContainer) {
            yield(field.metroAnnotations(ids, callingContainer = thisContainer))
          }
        }
      } else if (thisContainer is IrSimpleFunction) {
        correspondingPropertySymbol?.owner?.let { property ->
          if (property != callingContainer) {
            val propertyAnnotations =
              property.metroAnnotations(ids, callingContainer = thisContainer)
            yield(propertyAnnotations)
          }
        }
      } else if (thisContainer is IrField) {
        correspondingPropertySymbol?.owner?.let { property ->
          if (property != callingContainer) {
            val propertyAnnotations =
              property.metroAnnotations(ids, callingContainer = thisContainer)
            yield(propertyAnnotations)
          }
        }
      }
    }
    .reduce(MetroAnnotations<IrAnnotation>::mergeWith)
}

internal fun FirBasedSymbol<*>.metroAnnotations(
  session: FirSession
): MetroAnnotations<MetroFirAnnotation> {
  return metroAnnotations(session, null)
}

private fun FirBasedSymbol<*>.metroAnnotations(
  session: FirSession,
  callingContainer: FirBasedSymbol<*>?,
): MetroAnnotations<MetroFirAnnotation> {
  val ids = session.classIds
  var isDependencyGraph = false
  var isDependencyGraphFactory = false
  var isInject = false
  var isProvides = false
  var isBinds = false
  var isBindsInstance = false
  var isIntoSet = false
  var isElementsIntoSet = false
  var isIntoMap = false
  var isAssistedFactory = false
  var isComposable = false
  var multibinds: MetroFirAnnotation? = null
  var assisted: MetroFirAnnotation? = null
  var scope: MetroFirAnnotation? = null
  var qualifier: MetroFirAnnotation? = null
  val mapKeys = mutableSetOf<MetroFirAnnotation>()

  for (annotation in annotations.filter { it.isResolved }) {
    if (annotation !is FirAnnotationCall) continue
    val annotationType = annotation.resolvedType as? ConeClassLikeType ?: continue
    val annotationClass = annotationType.toClassSymbol(session) ?: continue
    val classId = annotationClass.classId

    when (this) {
      is FirValueParameterSymbol -> {
        // Only BindsInstance and Assisted go here
        when (classId) {
          in ids.providesAnnotations -> {
            isBindsInstance = true
            continue
          }
          in ids.assistedAnnotations -> {
            assisted =
              expectNullAndSet("assisted", assisted, MetroFirAnnotation(annotation, session))
            continue
          }
        }
      }

      is FirNamedFunctionSymbol,
      is FirPropertyAccessorSymbol,
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
            multibinds =
              expectNullAndSet("multibinds", assisted, MetroFirAnnotation(annotation, session))
            continue
          }
          Symbols.ClassIds.Composable -> {
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
      scope = expectNullAndSet("scope", scope, MetroFirAnnotation(annotation, session))
      continue
    } else if (annotationClass.isAnnotatedWithAny(session, ids.qualifierAnnotations)) {
      qualifier = expectNullAndSet("qualifier", qualifier, MetroFirAnnotation(annotation, session))
      continue
    } else if (annotationClass.isAnnotatedWithAny(session, ids.mapKeyAnnotations)) {
      mapKeys += MetroFirAnnotation(annotation, session)
      continue
    }
  }

  val annotations =
    MetroAnnotations(
      isDependencyGraph = isDependencyGraph,
      isDependencyGraphFactory = isDependencyGraphFactory,
      isInject = isInject,
      isProvides = isProvides,
      isBinds = isBinds,
      isBindsInstance = isBindsInstance,
      isIntoSet = isIntoSet,
      isElementsIntoSet = isElementsIntoSet,
      isIntoMap = isIntoMap,
      isAssistedFactory = isAssistedFactory,
      isComposable = isComposable,
      multibinds = multibinds,
      assisted = assisted,
      scope = scope,
      qualifier = qualifier,
      mapKeys = mapKeys,
      // This is never used in FIR so always null
      symbol = null,
    )

  val thisContainer = this

  return sequence {
      yield(annotations)

      // You can fit so many annotations in properties
      if (thisContainer is FirPropertySymbol) {
        // Retrieve annotations from this property's various accessors
        getterSymbol?.let { getter ->
          if (getter != callingContainer) {
            yield(getter.metroAnnotations(session, callingContainer = thisContainer))
          }
        }
        setterSymbol?.let { setter ->
          if (setter != callingContainer) {
            yield(setter.metroAnnotations(session, callingContainer = thisContainer))
          }
        }
        backingFieldSymbol?.let { field ->
          if (field != callingContainer) {
            yield(field.metroAnnotations(session, callingContainer = thisContainer))
          }
        }
      } else if (thisContainer is FirNamedFunctionSymbol) {
        // TODO?
        //  correspondingPropertySymbol?.owner?.let { property ->
        //    if (property != callingContainer) {
        //      val propertyAnnotations =
        //        property.metroAnnotations(ids, callingContainer = thisContainer)
        //      yield(propertyAnnotations)
        //    }
        //  }
      }
    }
    .reduce(MetroAnnotations<MetroFirAnnotation>::mergeWith)
}

internal fun <T> expectNullAndSet(type: String, current: T?, value: T): T {
  check(current == null) { "Multiple $type annotations found! Found $current and $value." }
  return value
}
