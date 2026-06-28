// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.graph.buildWrappedType
import dev.zacsweers.metro.compiler.graph.computeMultibindingId
import dev.zacsweers.metro.compiler.graph.createMapBindingId
import dev.zacsweers.metro.compiler.graph.mapTypes
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.qualifierAnnotation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.Variance

/** Renders a type as a stable, fully-qualified binding key string. */
internal fun KaSession.renderKeyType(type: KaType): String {
  return type.fullyExpandedType.render(
    KaTypeRendererForSource.WITH_QUALIFIED_NAMES,
    position = Variance.INVARIANT,
  )
}

/** Renders a type with short names for display purposes. */
internal fun KaSession.renderShortKeyType(type: KaType): String {
  return type.fullyExpandedType.render(
    KaTypeRendererForSource.WITH_SHORT_NAMES,
    position = Variance.INVARIANT,
  )
}

/** Builds a [KaTypeKey] for [type], capturing a restorable pointer and both renderings. */
internal fun KaSession.typeKey(type: KaType, qualifier: KaAnnotationSnapshot?): KaTypeKey {
  return KaTypeKey(typeSnapshot(type), qualifier)
}

/**
 * The `java.util.Optional<inner>` key a `@BindsOptionalOf` declaration exposes. Both the binding
 * and its consumers route through this one formula (see [consumedSite]) so the binding and consumer
 * keys are identical regardless of how the platform renders `java.util.Optional` for synthesized vs
 * source types.
 */
internal fun KaSession.optionalTypeKey(
  innerType: KaType,
  qualifier: KaAnnotationSnapshot?,
): KaTypeKey {
  val inner = innerType.fullyExpandedType
  val snapshot =
    KaTypeSnapshot(
      "${JAVA_OPTIONAL_CLASS_ID.asFqNameString()}<${renderKeyType(inner)}>",
      "${JAVA_OPTIONAL_CLASS_ID.shortClassName.asString()}<${renderShortKeyType(inner)}>",
      JAVA_OPTIONAL_CLASS_ID,
    )
  return KaTypeKey(snapshot, qualifier)
}

/** Builds a session-free type snapshot for the current analysis session. */
internal fun KaSession.typeSnapshot(type: KaType): KaTypeSnapshot {
  val expanded = type.fullyExpandedType
  return KaTypeSnapshot(
    renderKeyType(expanded),
    renderShortKeyType(expanded),
    (expanded as? KaClassType)?.classId,
  )
}

/** Builds a contextual key that preserves provider/lazy/map wrapper structure. */
internal fun KaSession.contextualTypeKey(
  type: KaType,
  qualifier: KaAnnotationSnapshot?,
  options: MetroOptions,
): KaContextualTypeKey {
  val declaredType = type.fullyExpandedType
  val rawSnapshot = typeSnapshot(declaredType)
  val wrappedType = declaredType.asWrappedType(options)
  val keySnapshot =
    when (wrappedType) {
      is WrappedType.Canonical -> wrappedType.type
      is WrappedType.Map -> rawSnapshot
      else -> wrappedType.canonicalType()
    }
  return KaContextualTypeKey(
    typeKey = KaTypeKey(keySnapshot, qualifier),
    wrappedType = wrappedType,
    rawType = rawSnapshot,
  )
}

context(session: KaSession)
private fun KaType.asWrappedType(options: MetroOptions): WrappedType<KaTypeSnapshot> {
  // Navigate over live KaTypes via the shared algorithm, then snapshot each node so the result can
  // outlive the analysis session. Mirrors FirContextualTypeKey/IrContextualTypeKey's asWrappedType.
  val wrapped =
    buildWrappedType(
      type = with(session) { fullyExpandedType },
      mapClassId = StandardClassIds.Map,
      providerTypes = options.providerTypes,
      lazyTypes = options.lazyTypes,
      classIdOf = { (it as? KaClassType)?.classId },
      argumentsOf = { type ->
        (type as? KaClassType)
          ?.typeArguments
          ?.mapNotNull { arg -> arg.type?.let { with(session) { it.fullyExpandedType } } }
          .orEmpty()
      },
    )
  return wrapped.mapTypes { session.typeSnapshot(it) }
}

internal fun KaSession.consumedSite(
  symbol: KaCallableSymbol,
  options: MetroOptions,
): ConsumedSite {
  val returnType = symbol.returnType.fullyExpandedType
  val qualifier = qualifierAnnotation(symbol, options)

  // A consumer of Optional<X> resolves to a @BindsOptionalOf (Dagger interop) binding. Key it with
  // the same formula the binding uses so the two can't desync on Optional rendering.
  val optionalInner = optionalInnerType(returnType, options)
  if (optionalInner != null) {
    val optionalKey = optionalTypeKey(optionalInner, qualifier)
    val contextKey =
      KaContextualTypeKey(
        optionalKey,
        WrappedType.Canonical(optionalKey.type),
        rawType = optionalKey.type,
      )
    return ConsumedSite(contextKey, isAbstractType = false, typeClassId = JAVA_OPTIONAL_CLASS_ID)
  }

  val contextKey = contextualTypeKey(returnType, qualifier, options)
  val classSymbol = contextKey.typeKey.type.classId?.let { findClass(it) }
  val isAbstract =
    classSymbol != null &&
      (classSymbol.classKind == KaClassKind.INTERFACE ||
        classSymbol.modality == KaSymbolModality.ABSTRACT)
  return ConsumedSite(
    contextKey,
    isAbstract,
    aggregateMultibindingId(
      (returnType as? KaClassType)?.aggregateType(options),
      contextKey,
      options,
    ),
    contextKey.typeKey.type.classId,
  )
}

/** The `X` of a `java.util.Optional<X>` consumed type, when Dagger interop is enabled. */
private fun optionalInnerType(type: KaType, options: MetroOptions): KaType? {
  if (!options.enableDaggerRuntimeInterop) return null
  val classType = type as? KaClassType ?: return null
  if (classType.classId != JAVA_OPTIONAL_CLASS_ID) return null
  return classType.typeArguments.firstOrNull()?.type
}

/**
 * Peels `Provider`/`Lazy` wrappers to the underlying aggregate type, so wrapped multibinding
 * consumers (`Provider<Set<E>>`, `Lazy<Map<K, V>>`, etc.) are detected just like bare ones.
 */
context(session: KaSession)
private fun KaClassType.aggregateType(options: MetroOptions): KaClassType {
  val classId = this.classId
  if (classId in options.providerTypes || classId in options.lazyTypes) {
    val innerType = typeArguments.firstOrNull()?.type ?: return this
    val inner = with(session) { innerType.fullyExpandedType } as? KaClassType ?: return this
    return inner.aggregateType(options)
  }
  return this
}

/** Computes the multibinding id collected by a `Set<E>`/`Map<K, V>` consumer site, if any. */
private fun KaSession.aggregateMultibindingId(
  classType: KaClassType?,
  contextKey: KaContextualTypeKey,
  options: MetroOptions,
): String? {
  if (classType == null) return null
  return when (classType.classId) {
    StandardClassIds.Set -> {
      val elementType = classType.typeArguments.firstOrNull()?.type ?: return null
      val elementKeyType = elementType.asWrappedType(options).canonicalType()
      contextKey.typeKey.copy(type = elementKeyType).computeMultibindingId()
    }
    StandardClassIds.Map -> {
      // The Map node may sit under Provider/Lazy wrappers in the contextual key's wrapped type.
      val wrappedMap =
        contextKey.wrappedType.innerTypesSequence
          .filterIsInstance<WrappedType.Map<KaTypeSnapshot>>()
          .firstOrNull() ?: return null
      val valueType = wrappedMap.valueType.canonicalType()
      createMapBindingId(wrappedMap.keyType.renderedType, contextKey.typeKey.copy(type = valueType))
    }
    else -> null
  }
}
