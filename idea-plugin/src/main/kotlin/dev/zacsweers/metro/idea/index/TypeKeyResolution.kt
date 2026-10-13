// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.graph.buildWrappedType
import dev.zacsweers.metro.compiler.graph.identity.Nullability
import dev.zacsweers.metro.compiler.graph.identity.ProjectionVariance
import dev.zacsweers.metro.compiler.graph.identity.TypeArgumentIdentity
import dev.zacsweers.metro.compiler.graph.identity.TypeArgumentNode
import dev.zacsweers.metro.compiler.graph.identity.TypeIdentity
import dev.zacsweers.metro.compiler.graph.identity.TypeNode
import dev.zacsweers.metro.compiler.graph.identity.TypeReader
import dev.zacsweers.metro.compiler.graph.identity.typeIdentity
import dev.zacsweers.metro.compiler.graph.mapTypes
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaTypeArgumentSnapshot
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.model.multibindingId
import dev.zacsweers.metro.idea.qualifierAnnotation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.KaExpandedTypeRenderingMode
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaClassErrorType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaIntersectionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeArgumentWithVariance
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.analysis.api.types.KaTypeProjection
import org.jetbrains.kotlin.analysis.api.types.KaUnresolvedClassTypeQualifier
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.Variance

// Expanded KaTypes retain alias abbreviations. Keys must expand aliases at every argument depth.
private val KEY_TYPE_RENDERER =
  KaTypeRendererForSource.WITH_QUALIFIED_NAMES.with {
    expandedTypeRenderingMode = KaExpandedTypeRenderingMode.RENDER_EXPANDED_TYPE
  }

/** Renders a type as a stable, fully-qualified binding key string. */
internal fun KaSession.renderKeyType(type: KaType): String {
  return type.fullyExpandedType.render(KEY_TYPE_RENDERER, position = Variance.INVARIANT)
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
  val innerSnapshot = typeSnapshot(inner)
  val identity =
    TypeIdentity.Class(
      JAVA_OPTIONAL_CLASS_ID.asString(),
      listOf(TypeArgumentIdentity.Projection(ProjectionVariance.Invariant, innerSnapshot.identity)),
    )
  val snapshot =
    KaTypeSnapshot(
      "${JAVA_OPTIONAL_CLASS_ID.asFqNameString()}<${renderKeyType(inner)}>",
      "${JAVA_OPTIONAL_CLASS_ID.shortClassName.asString()}<${renderShortKeyType(inner)}>",
      JAVA_OPTIONAL_CLASS_ID,
      identity,
      listOf(KaTypeArgumentSnapshot.Typed(innerSnapshot, Variance.INVARIANT)),
    )
  return KaTypeKey(snapshot, qualifier)
}

/** Builds a session-free type snapshot for the current analysis session. */
internal fun KaSession.typeSnapshot(type: KaType): KaTypeSnapshot {
  val expanded = type.fullyExpandedType
  val classType = expanded as? KaClassType
  val classId = classType?.classId ?: (expanded.expandedSymbol as? KaNamedClassSymbol)?.classId
  return KaTypeSnapshot(
    renderKeyType(expanded),
    renderShortKeyType(expanded),
    classId,
    typeIdentity(expanded),
    classType?.typeArguments.orEmpty().map { argument ->
      when (argument) {
        is KaTypeArgumentWithVariance ->
          KaTypeArgumentSnapshot.Typed(typeSnapshot(argument.type), argument.variance)
        else -> KaTypeArgumentSnapshot.Star
      }
    },
    isMarkedNullable = classType?.isMarkedNullable == true,
    isError = expanded is KaErrorType,
    unresolvedClassName =
      (expanded as? KaClassErrorType)
        ?.qualifiers
        ?.lastOrNull { it is KaUnresolvedClassTypeQualifier }
        ?.name
        ?.takeUnless { it.isSpecial },
  )
}

/** Identity of [type]. Type aliases are expanded at every depth. */
internal fun KaSession.typeIdentity(type: KaType): TypeIdentity =
  KaTypeReader(this).typeIdentity(type)

private class KaTypeReader(private val session: KaSession) : TypeReader<KaType> {
  override fun read(type: KaType): TypeNode<KaType> =
    with(session) {
      when (val expanded = type.fullyExpandedType) {
        is KaErrorType -> TypeNode.Unresolved(renderKeyType(expanded))
        is KaClassType -> {
          val arguments =
            expanded.typeArguments.map { argument ->
              when (argument) {
                is KaTypeArgumentWithVariance ->
                  TypeArgumentNode.Projection(
                    argument.variance.toProjectionVariance(),
                    argument.type,
                  )
                else -> TypeArgumentNode.Star
              }
            }
          TypeNode.Class(expanded.classId.asString(), arguments, expanded.nullability())
        }
        is KaTypeParameterType -> typeParameterNode(expanded, expanded.nullability())
        is KaDefinitelyNotNullType -> {
          val original = expanded.original
          if (original is KaTypeParameterType) {
            typeParameterNode(original, Nullability.DefinitelyNotNull)
          } else {
            TypeNode.Unresolved(renderKeyType(expanded))
          }
        }
        is KaFlexibleType -> TypeNode.Flexible(expanded.lowerBound, expanded.upperBound)
        is KaIntersectionType -> TypeNode.Intersection(expanded.conjuncts)
        else -> TypeNode.Unresolved(renderKeyType(expanded))
      }
    }

  private fun KaSession.typeParameterNode(
    type: KaTypeParameterType,
    nullability: Nullability,
  ): TypeNode<KaType> {
    val symbol = type.symbol
    val ownerId =
      when (val owner = symbol.containingDeclaration) {
        is KaClassLikeSymbol -> owner.classId?.asString().orEmpty()
        is KaCallableSymbol -> owner.callableId?.toString().orEmpty()
        else -> ""
      }
    return TypeNode.TypeParameter(
      ownerId = ownerId,
      // Names are unique within an owner, so the IDE doesn't track positions.
      index = -1,
      name = type.name.asString(),
      nullability = nullability,
    )
  }

  private fun KaType.nullability(): Nullability =
    if (with(session) { isMarkedNullable }) {
      Nullability.Nullable
    } else {
      Nullability.NotNull
    }

  private fun Variance.toProjectionVariance(): ProjectionVariance =
    when (this) {
      Variance.INVARIANT -> ProjectionVariance.Invariant
      Variance.IN_VARIANCE -> ProjectionVariance.In
      Variance.OUT_VARIANCE -> ProjectionVariance.Out
    }
}

/** Rebuilds a concrete request inside its current, short-lived analysis session. */
internal fun KaSession.restoreClassType(snapshot: KaTypeSnapshot): KaClassType? {
  val classId = snapshot.classId ?: return null
  val typeArguments = ArrayList<KaTypeProjection>(snapshot.typeArguments.size)
  for (typeArgument in snapshot.typeArguments) {
    val projection =
      when (typeArgument) {
        KaTypeArgumentSnapshot.Star -> typeCreator.starTypeProjection()
        is KaTypeArgumentSnapshot.Typed -> {
          val restored = restoreClassType(typeArgument.type) ?: return null
          typeCreator.typeProjection(typeArgument.variance, restored)
        }
      }
    typeArguments += projection
  }
  return typeCreator.classType(classId) {
    isMarkedNullable = snapshot.isMarkedNullable
    for (projection in typeArguments) typeArgument(projection)
  } as? KaClassType
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
      suspendProviderTypes = options.suspendProviderModelingTypes,
      suspendLazyTypes = options.suspendLazyTypes,
      classIdOf = { type ->
        (type as? KaClassType)?.classId
          ?: (with(session) { type.expandedSymbol } as? KaNamedClassSymbol)?.classId
      },
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
  return consumedSite(symbol.returnType, symbol, options)
}

/** Resolves a consuming site using [type] after use-site generic substitution. */
internal fun KaSession.consumedSite(
  type: KaType,
  symbol: KaCallableSymbol,
  options: MetroOptions,
): ConsumedSite {
  val returnType = type.fullyExpandedType
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
    contextKey.multibindingId(),
    contextKey.typeKey.type.classId,
  )
}

/**
 * The contextual key a binding depends on for [symbol]. Optional sites are marked via
 * [KaContextualTypeKey.hasDefault].
 */
internal fun KaSession.dependencyKey(
  symbol: KaCallableSymbol,
  options: MetroOptions,
): KaContextualTypeKey {
  return dependencyKey(symbol.returnType, symbol, options)
}

/** Resolves a dependency key using [type] after use-site generic substitution. */
internal fun KaSession.dependencyKey(
  type: KaType,
  symbol: KaCallableSymbol,
  options: MetroOptions,
): KaContextualTypeKey {
  val site = consumedSite(type, symbol, options)
  return site.contextKey.withDefault(symbol.isOptionalConsumer(options))
}

/** The `X` of a `java.util.Optional<X>` consumed type, when Dagger interop is enabled. */
private fun optionalInnerType(type: KaType, options: MetroOptions): KaType? {
  if (!options.enableDaggerRuntimeInterop) return null
  val classType = type as? KaClassType ?: return null
  if (classType.classId != JAVA_OPTIONAL_CLASS_ID) return null
  return classType.typeArguments.firstOrNull()?.type
}
