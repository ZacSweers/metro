// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroAnnotations.Kind
import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isResolved
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.asIrAnnotation
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.findInjectableConstructor
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.EnumSet
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
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.parentAsClass

@Poko
internal class MetroAnnotations<T>(
  val isDependencyGraph: Boolean = false,
  val isDependencyGraphFactory: Boolean = false,
  val isInject: Boolean = false,
  val isAssistedInject: Boolean = false,
  val isProvides: Boolean = false,
  val isBinds: Boolean = false,
  val isBindsInstance: Boolean = false,
  val isIntoSet: Boolean = false,
  val isElementsIntoSet: Boolean = false,
  val isIntoMap: Boolean = false,
  val isAssistedFactory: Boolean = false,
  val isComposable: Boolean = false,
  val isBindsOptionalOf: Boolean = false,
  val isOptionalBinding: Boolean = false,
  val multibinds: T? = null,
  val assisted: T? = null,
  val scope: T? = null,
  val qualifier: T? = null,
  val mapKeys: Set<T> = emptySet(),
  // An IrAnnotation or FirAnnotation
  // TODO the lack of a type here is unfortunate
  @Poko.Skip val symbol: Any? = null,
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
    isAssistedInject: Boolean = this.isAssistedInject,
    isProvides: Boolean = this.isProvides,
    isBinds: Boolean = this.isBinds,
    isBindsInstance: Boolean = this.isBindsInstance,
    isIntoSet: Boolean = this.isIntoSet,
    isElementsIntoSet: Boolean = this.isElementsIntoSet,
    isIntoMap: Boolean = this.isIntoMap,
    isAssistedFactory: Boolean = this.isAssistedFactory,
    isComposable: Boolean = this.isComposable,
    isBindsOptionalOf: Boolean = this.isBindsOptionalOf,
    isOptionalBinding: Boolean = this.isOptionalBinding,
    multibinds: T? = this.multibinds,
    assisted: T? = this.assisted,
    scope: T? = this.scope,
    qualifier: T? = this.qualifier,
    mapKeys: Set<T> = this.mapKeys,
  ): MetroAnnotations<T> {
    return MetroAnnotations(
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
      isAssistedFactory = isAssistedFactory,
      isComposable = isComposable,
      isBindsOptionalOf = isBindsOptionalOf,
      isOptionalBinding = isOptionalBinding,
      multibinds = multibinds,
      assisted = assisted,
      scope = scope,
      qualifier = qualifier,
      mapKeys = mapKeys,
      symbol = symbol,
    )
  }

  fun mergeWith(other: MetroAnnotations<T>): MetroAnnotations<T> =
    copy(
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
      isAssistedFactory = isAssistedFactory || other.isAssistedFactory,
      multibinds = multibinds ?: other.multibinds,
      assisted = assisted ?: other.assisted,
      scope = scope ?: other.scope,
      qualifier = qualifier ?: other.qualifier,
      mapKeys = mapKeys + other.mapKeys,
    )

  enum class Kind {
    DependencyGraph,
    DependencyGraphFactory,
    Inject,
    AssistedInject,
    Provides,
    Binds,
    BindsInstance,
    IntoSet,
    ElementsIntoSet,
    IntoMap,
    AssistedFactory,
    Composable,
    Multibinds,
    Assisted,
    Scope,
    Qualifier,
    MapKey,
    BindsOptionalOf,
    OptionalBinding,
  }

  companion object {
    internal val ALL_KINDS = EnumSet.allOf(Kind::class.java)

    private val NONE =
      MetroAnnotations<Any>(
        isDependencyGraph = false,
        isDependencyGraphFactory = false,
        isInject = false,
        isAssistedInject = false,
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

private fun kindSetOf(vararg kinds: Kind): Set<Kind> {
  return if (kinds.isEmpty()) {
    MetroAnnotations.ALL_KINDS
  } else if (kinds.size == 1) {
    EnumSet.of(kinds[0])
  } else {
    EnumSet.of(kinds[0], *kinds.copyOfRange(1, kinds.size))
  }
}

internal fun IrAnnotationContainer.metroAnnotations(
  ids: ClassIds,
  vararg kinds: Kind,
): MetroAnnotations<IrAnnotation> {
  return metroAnnotations(ids, kindSetOf(*kinds))
}

internal fun IrAnnotationContainer.metroAnnotations(
  ids: ClassIds,
  kinds: Set<Kind> = MetroAnnotations.ALL_KINDS,
): MetroAnnotations<IrAnnotation> = metroAnnotations(ids, null, kinds)

private fun IrAnnotationContainer.metroAnnotations(
  ids: ClassIds,
  callingContainer: IrAnnotationContainer?,
  kinds: Set<Kind>,
): MetroAnnotations<IrAnnotation> {
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
  var isAssistedFactory = false
  var isComposable = false
  var isBindsOptionalOf = false
  var isOptionalBinding = false
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
          in ids.providesAnnotations if (Kind.Provides in kinds) -> {
            isBindsInstance = true
            continue
          }
          in ids.assistedAnnotations if (Kind.Assisted in kinds) -> {
            assisted = expectNullAndSet("assisted", assisted, annotation.asIrAnnotation())
            continue
          }
          in ids.optionalBindingAnnotations if (Kind.OptionalBinding in kinds) -> {
            isOptionalBinding = true
            continue
          }
        }
      }

      is IrFunction,
      is IrProperty -> {
        // Binds, Provides
        when (classId) {
          in ids.bindsAnnotations if (Kind.Binds in kinds) -> {
            isBinds = true
            continue
          }
          in ids.providesAnnotations if (Kind.Provides in kinds) -> {
            isProvides = true
            continue
          }
          in ids.intoSetAnnotations if (Kind.IntoSet in kinds) -> {
            isIntoSet = true
            continue
          }
          in ids.elementsIntoSetAnnotations if (Kind.ElementsIntoSet in kinds) -> {
            isElementsIntoSet = true
            continue
          }
          in ids.intoMapAnnotations if (Kind.IntoMap in kinds) -> {
            isIntoMap = true
            continue
          }
          in ids.multibindsAnnotations -> {
            multibinds = expectNullAndSet("multibindings", multibinds, annotation.asIrAnnotation())
            continue
          }
          Symbols.ClassIds.Composable if (Kind.Composable in kinds) -> {
            isComposable = true
            continue
          }
          DaggerSymbols.ClassIds.DAGGER_BINDS_OPTIONAL_OF if (Kind.BindsOptionalOf in kinds) -> {
            isBindsOptionalOf = true
            continue
          }
          in ids.optionalBindingAnnotations if (Kind.OptionalBinding in kinds) -> {
            isOptionalBinding = true
            continue
          }
        }
      }

      is IrClass -> {
        // AssistedFactory, DependencyGraph, DependencyGraph.Factory
        when (classId) {
          in ids.assistedFactoryAnnotations if (Kind.AssistedFactory in kinds) -> {
            isAssistedFactory = true
            continue
          }
          in ids.dependencyGraphAnnotations if (Kind.DependencyGraph in kinds) -> {
            isDependencyGraph = true
            continue
          }
          in ids.dependencyGraphFactoryAnnotations if (Kind.DependencyGraphFactory in kinds) -> {
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

    if (Kind.AssistedInject in kinds && classId in ids.assistedInjectAnnotations) {
      isAssistedInject = true
      continue
    }

    if (Kind.Scope in kinds && annotationClass.isAnnotatedWithAny(ids.scopeAnnotations)) {
      scope = expectNullAndSet("scope", scope, annotation.asIrAnnotation())
      continue
    } else if (
      Kind.Qualifier in kinds && annotationClass.isAnnotatedWithAny(ids.qualifierAnnotations)
    ) {
      qualifier = expectNullAndSet("qualifier", qualifier, annotation.asIrAnnotation())
      continue
    } else if (Kind.MapKey in kinds && annotationClass.isAnnotatedWithAny(ids.mapKeyAnnotations)) {
      mapKeys += annotation.asIrAnnotation()
      continue
    }
  }

  val annotations =
    MetroAnnotations(
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
      isAssistedFactory = isAssistedFactory,
      isComposable = isComposable,
      isBindsOptionalOf = isBindsOptionalOf,
      isOptionalBinding = isOptionalBinding,
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
      when (thisContainer) {
        is IrProperty -> {
          // Retrieve annotations from this property's various accessors
          getter?.let { getter ->
            if (getter != callingContainer) {
              yield(getter.metroAnnotations(ids, callingContainer = thisContainer, kinds = kinds))
            }
          }
          setter?.let { setter ->
            if (setter != callingContainer) {
              yield(setter.metroAnnotations(ids, callingContainer = thisContainer, kinds = kinds))
            }
          }
          backingField?.let { field ->
            if (field != callingContainer) {
              yield(field.metroAnnotations(ids, callingContainer = thisContainer, kinds = kinds))
            }
          }
        }

        is IrSimpleFunction -> {
          correspondingPropertySymbol?.owner?.let { property ->
            if (property != callingContainer) {
              val propertyAnnotations =
                property.metroAnnotations(ids, callingContainer = thisContainer, kinds = kinds)
              yield(propertyAnnotations)
            }
          }
        }

        is IrField -> {
          correspondingPropertySymbol?.owner?.let { property ->
            if (property != callingContainer) {
              val propertyAnnotations =
                property.metroAnnotations(ids, callingContainer = thisContainer, kinds = kinds)
              yield(propertyAnnotations)
            }
          }
        }

        is IrConstructor -> {
          // Read from the class too
          parentAsClass.let { parentClass ->
            if (parentClass != callingContainer) {
              val classAnnotations =
                parentClass.metroAnnotations(ids, callingContainer = thisContainer, kinds = kinds)
              yield(classAnnotations)
            }
          }
        }

        is IrClass -> {
          // Read from the inject constructor too
          val constructor =
            findInjectableConstructor(onlyUsePrimaryConstructor = false, ids.injectAnnotations)
          if (constructor != null) {
            if (constructor != callingContainer) {
              val constructorAnnotations =
                constructor.metroAnnotations(ids, callingContainer = thisContainer, kinds = kinds)
              yield(constructorAnnotations)
            }
          }
        }
      }
    }
    .reduce(MetroAnnotations<IrAnnotation>::mergeWith)
}

internal fun FirBasedSymbol<*>.metroAnnotations(
  session: FirSession,
  vararg kinds: Kind,
): MetroAnnotations<MetroFirAnnotation> {
  return metroAnnotations(session, kindSetOf(*kinds))
}

internal fun FirBasedSymbol<*>.metroAnnotations(
  session: FirSession,
  kinds: Set<Kind> = MetroAnnotations.ALL_KINDS,
): MetroAnnotations<MetroFirAnnotation> {
  return metroAnnotations(session, null, kinds)
}

private fun FirBasedSymbol<*>.metroAnnotations(
  session: FirSession,
  callingContainer: FirBasedSymbol<*>?,
  kinds: Set<Kind>,
): MetroAnnotations<MetroFirAnnotation> {
  val ids = session.classIds
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
  var isAssistedFactory = false
  var isComposable = false
  var isBindsOptionalOf = false
  var isOptionalBinding = false
  var multibinds: MetroFirAnnotation? = null
  var assisted: MetroFirAnnotation? = null
  var scope: MetroFirAnnotation? = null
  var qualifier: MetroFirAnnotation? = null
  val mapKeys = mutableSetOf<MetroFirAnnotation>()

  for (annotation in resolvedCompilerAnnotationsWithClassIds.filter { it.isResolved }) {
    if (annotation !is FirAnnotationCall) continue
    val annotationType = annotation.resolvedType as? ConeClassLikeType ?: continue
    val annotationClass = annotationType.toClassSymbol(session) ?: continue
    val classId = annotationClass.classId

    when (this) {
      is FirValueParameterSymbol -> {
        // Only BindsInstance and Assisted go here
        when (classId) {
          in ids.providesAnnotations if (Kind.Provides in kinds) -> {
            isBindsInstance = true
            continue
          }
          in ids.assistedAnnotations if (Kind.Assisted in kinds) -> {
            assisted =
              expectNullAndSet("assisted", assisted, MetroFirAnnotation(annotation, session))
            continue
          }
          in ids.optionalBindingAnnotations if (Kind.OptionalBinding in kinds) -> {
            isOptionalBinding = true
            continue
          }
        }
      }

      is FirNamedFunctionSymbol,
      is FirPropertyAccessorSymbol,
      is FirPropertySymbol -> {
        // Binds, Provides
        when (classId) {
          in ids.bindsAnnotations if (Kind.Binds in kinds) -> {
            isBinds = true
            continue
          }
          in ids.providesAnnotations if (Kind.Provides in kinds) -> {
            isProvides = true
            continue
          }
          in ids.intoSetAnnotations if (Kind.IntoSet in kinds) -> {
            isIntoSet = true
            continue
          }
          in ids.elementsIntoSetAnnotations if (Kind.ElementsIntoSet in kinds) -> {
            isElementsIntoSet = true
            continue
          }
          in ids.intoMapAnnotations if (Kind.IntoMap in kinds) -> {
            isIntoMap = true
            continue
          }
          in ids.multibindsAnnotations if (Kind.Multibinds in kinds) -> {
            multibinds =
              expectNullAndSet("multibinds", assisted, MetroFirAnnotation(annotation, session))
            continue
          }
          Symbols.ClassIds.Composable if (Kind.Composable in kinds) -> {
            isComposable = true
            continue
          }
          DaggerSymbols.ClassIds.DAGGER_BINDS_OPTIONAL_OF if
            (session.metroFirBuiltIns.options.enableDaggerRuntimeInterop &&
              Kind.BindsOptionalOf in kinds)
           -> {
            isBindsOptionalOf = true
            continue
          }
          in ids.optionalBindingAnnotations if (Kind.OptionalBinding in kinds) -> {
            isOptionalBinding = true
            continue
          }
        }
      }

      is FirClassSymbol<*> -> {
        // AssistedFactory, DependencyGraph, DependencyGraph.Factory
        if (Kind.AssistedFactory in kinds && classId in ids.assistedFactoryAnnotations) {
          isAssistedFactory = true
          continue
        } else if (Kind.DependencyGraph in kinds && classId in ids.dependencyGraphAnnotations) {
          isDependencyGraph = true
          continue
        } else if (
          Kind.DependencyGraphFactory in kinds && classId in ids.dependencyGraphFactoryAnnotations
        ) {
          isDependencyGraphFactory = true
          continue
        }
      }
    }

    // Everything below applies to multiple targets

    if (Kind.Inject in kinds && classId in ids.injectAnnotations) {
      isInject = true
      continue
    }

    if (Kind.AssistedInject in kinds && classId in ids.assistedInjectAnnotations) {
      isAssistedInject = true
      continue
    }

    if (Kind.Scope in kinds && annotationClass.isAnnotatedWithAny(session, ids.scopeAnnotations)) {
      scope = expectNullAndSet("scope", scope, MetroFirAnnotation(annotation, session))
      continue
    } else if (
      Kind.Qualifier in kinds &&
        annotationClass.isAnnotatedWithAny(session, ids.qualifierAnnotations)
    ) {
      qualifier = expectNullAndSet("qualifier", qualifier, MetroFirAnnotation(annotation, session))
      continue
    } else if (
      Kind.MapKey in kinds && annotationClass.isAnnotatedWithAny(session, ids.mapKeyAnnotations)
    ) {
      mapKeys += MetroFirAnnotation(annotation, session)
      continue
    }
  }

  val annotations =
    MetroAnnotations(
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
      isAssistedFactory = isAssistedFactory,
      isComposable = isComposable,
      isBindsOptionalOf = isBindsOptionalOf,
      isOptionalBinding = isOptionalBinding,
      multibinds = multibinds,
      assisted = assisted,
      scope = scope,
      qualifier = qualifier,
      mapKeys = mapKeys,
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
            yield(getter.metroAnnotations(session, callingContainer = thisContainer, kinds = kinds))
          }
        }
        setterSymbol?.let { setter ->
          if (setter != callingContainer) {
            yield(setter.metroAnnotations(session, callingContainer = thisContainer, kinds = kinds))
          }
        }
        backingFieldSymbol?.let { field ->
          if (field != callingContainer) {
            yield(field.metroAnnotations(session, callingContainer = thisContainer, kinds = kinds))
          }
        }
      } else if (thisContainer is FirNamedFunctionSymbol) {
        // TODO?
        //  correspondingPropertySymbol?.owner?.let { property ->
        //    if (property != callingContainer) {
        //      val propertyAnnotations =
        //        property.metroAnnotations(ids, callingContainer = thisContainer, kinds = kinds)
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

/** Returns a list of annotations for copying to mirror functions. */
context(context: IrMetroContext)
internal fun MetroAnnotations<IrAnnotation>.mirrorIrConstructorCalls(
  symbol: IrSymbol
): List<IrConstructorCall> {
  return buildList {
    if (isProvides) {
      add(buildAnnotation(symbol, context.metroSymbols.providesConstructor))
    } else if (isBinds) {
      add(buildAnnotation(symbol, context.metroSymbols.bindsConstructor))
    }
    if (isIntoSet) {
      add(buildAnnotation(symbol, context.metroSymbols.intoSetConstructor))
    } else if (isElementsIntoSet) {
      add(buildAnnotation(symbol, context.metroSymbols.elementsIntoSetConstructor))
    } else if (isIntoMap) {
      add(buildAnnotation(symbol, context.metroSymbols.intoMapConstructor))
    } else if (isBindsOptionalOf) {
      add(buildAnnotation(symbol, context.metroSymbols.bindsOptionalConstructor))
    }
    scope?.let { add(it.ir.deepCopyWithSymbols()) }
    qualifier?.let { add(it.ir.deepCopyWithSymbols()) }
    multibinds?.let { add(it.ir.deepCopyWithSymbols()) }
    addAll(mapKeys.map { it.ir.deepCopyWithSymbols() })
  }
}
