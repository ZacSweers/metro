// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.graph.buildWrappedType
import dev.zacsweers.metro.compiler.graph.computeMultibindingId
import dev.zacsweers.metro.compiler.graph.createMapBindingId
import dev.zacsweers.metro.compiler.graph.mapTypes
import dev.zacsweers.metro.idea.model.BindingKind
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.types.Variance

// ---------------------------------------------------------------------------------------------
// Shared Analysis API helpers
// ---------------------------------------------------------------------------------------------

internal val SET_CLASS_ID = ClassId.fromString("kotlin/collections/Set")
internal val MAP_CLASS_ID = ClassId.fromString("kotlin/collections/Map")
// Dagger interop: `@BindsOptionalOf fun foo(): Foo` makes `java.util.Optional<Foo>` available,
// mirroring the compiler's IrBinding.CustomWrapper. Only active when Dagger runtime interop is on.
private val DAGGER_BINDS_OPTIONAL_OF = ClassId.fromString("dagger/BindsOptionalOf")
internal val JAVA_OPTIONAL_CLASS_ID = ClassId.fromString("java/util/Optional")

internal fun bindsOptionalOfAnnotations(options: MetroOptions): Set<ClassId> {
  return if (options.enableDaggerRuntimeInterop) setOf(DAGGER_BINDS_OPTIONAL_OF) else emptySet()
}

private val COLLECTION_LIKE_CLASS_IDS =
  setOf(
    SET_CLASS_ID,
    ClassId.fromString("kotlin/collections/Collection"),
    ClassId.fromString("kotlin/collections/List"),
    ClassId.fromString("kotlin/collections/Iterable"),
  )

internal fun bindingContributionAnnotations(options: MetroOptions): Set<ClassId> {
  return buildSet {
    addAll(options.contributesBindingAnnotations)
    addAll(options.contributesIntoSetAnnotations)
    addAll(options.customContributesIntoSetAnnotations)
    addAll(options.contributesIntoMapAnnotations)
  }
}

internal fun nonAccessorCallableAnnotations(options: MetroOptions): Set<ClassId> {
  return buildSet {
    addAll(options.bindsAnnotations)
    addAll(options.providesAnnotations)
    addAll(options.multibindsAnnotations)
  }
}

/**
 * Mirrors the compiler's `findInjectConstructorsImpl`: only regular (non-sealed, non-abstract)
 * classes are constructor-injectable, regardless of where the inject annotation sits.
 */
internal fun KaNamedClassSymbol.isInjectableKind(): Boolean {
  return classKind == KaClassKind.CLASS &&
    (modality == KaSymbolModality.FINAL || modality == KaSymbolModality.OPEN)
}

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
      mapClassId = MAP_CLASS_ID,
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
  val classSymbol = contextKey.typeKey.type.classId?.let { findClass(it) } as? KaClassSymbol
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
    SET_CLASS_ID -> {
      val elementType = classType.typeArguments.firstOrNull()?.type ?: return null
      val elementKeyType = elementType.asWrappedType(options).canonicalType()
      contextKey.typeKey.copy(type = elementKeyType).computeMultibindingId()
    }
    MAP_CLASS_ID -> {
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

/**
 * Resolves the map key type of an `@IntoMap` contribution from its map key annotation, mirroring
 * the compiler's `mapKeyType`: the annotation's single member type when the `@MapKey`
 * meta-annotation has `unwrapValue = true` (the default), otherwise the annotation type itself.
 */
internal fun KaSession.mapKeyType(annotated: KaAnnotated, options: MetroOptions): String? {
  for (annotation in annotated.annotations) {
    val classId = annotation.classId ?: continue
    val annotationClass = findClass(classId) as? KaNamedClassSymbol ?: continue
    val mapKeyMeta =
      annotationClass.annotations.firstOrNull { it.classId in options.mapKeyAnnotations }
        ?: continue
    val unwrapValue =
      mapKeyMeta.arguments
        .firstOrNull { it.name.asString() == "unwrapValue" }
        ?.let { (it.expression as? KaAnnotationValue.ConstantValue)?.value?.value } != false
    val keyType =
      if (unwrapValue) {
        val constructor =
          annotationClass.memberScope.constructors.firstOrNull { it.isPrimary } ?: continue
        constructor.valueParameters.firstOrNull()?.returnType ?: continue
      } else {
        annotationClass.defaultType
      }
    return renderKeyType(keyType)
  }
  return null
}

/**
 * Computes the bindings originated by [declaration]: `@Provides`/`@Binds`/`@Multibinds` callables,
 * injected classes, contributed bindings, and instance-binding factory parameters.
 */
internal fun KaSession.bindingData(
  declaration: KtDeclaration,
  options: MetroOptions,
): List<BindingData> {
  return when (declaration) {
    is KtPropertyAccessor -> bindingData(declaration.property, options)
    is KtNamedFunction,
    is KtProperty -> callableBindingData(declaration as KtCallableDeclaration, options)
    is KtParameter -> instanceBindingData(declaration, options)
    is KtClassOrObject -> classBindingData(declaration, options)
    is KtConstructor<*> -> classBindingData(declaration.getContainingClassOrObject(), options)
    else -> emptyList()
  }
}

private fun KaSession.callableBindingData(
  declaration: KtCallableDeclaration,
  options: MetroOptions,
): List<BindingData> {
  val symbol = declaration.symbol as? KaCallableSymbol ?: return emptyList()
  val getterSymbol = (symbol as? KaPropertySymbol)?.getter

  fun has(classIds: Set<ClassId>): Boolean {
    return symbol.hasAnyAnnotation(classIds) || getterSymbol?.hasAnyAnnotation(classIds) == true
  }

  val qualifier = qualifierAnnotation(symbol, options)
  val scope = scopeAnnotation(symbol, options)
  val returnType = symbol.returnType

  // Mirrors the compiler's transformIfIntoMultibinding: a contribution keeps its element key as
  // declared and joins its aggregate by id.
  fun multibindingId(elementKey: KaTypeKey): String? {
    return when {
      has(options.intoMapAnnotations) -> {
        val mapKeyType =
          mapKeyType(symbol, options)
            ?: getterSymbol?.let { mapKeyType(it, options) }
            ?: return null
        createMapBindingId(mapKeyType, elementKey)
      }
      has(options.intoSetAnnotations) || has(options.elementsIntoSetAnnotations) ->
        elementKey.computeMultibindingId()
      else -> null
    }
  }

  return when {
    has(options.bindsAnnotations) -> {
      val sourceType =
        symbol.receiverParameter?.returnType
          ?: (symbol as? KaNamedFunctionSymbol)?.valueParameters?.singleOrNull()?.returnType
          ?: return emptyList()
      val sourceParam = (symbol as? KaNamedFunctionSymbol)?.valueParameters?.singleOrNull()
      val consumedKey = typeKey(sourceType, sourceParam?.let { qualifierAnnotation(it, options) })
      val implementationName =
        (sourceType.fullyExpandedType as? KaClassType)?.classId?.shortClassName?.asString()
      val elementKey = typeKey(returnType, qualifier)
      val multibindingId = multibindingId(elementKey)
      listOf(
        BindingData(
          elementKey,
          if (multibindingId != null) {
            BindingKind.MULTIBINDING_CONTRIBUTION
          } else {
            BindingKind.BINDS
          },
          scope,
          implementationName,
          consumedKey,
          multibindingId,
        )
      )
    }
    has(bindsOptionalOfAnnotations(options)) -> {
      // `@BindsOptionalOf fun foo(): Foo` exposes `Optional<Foo>`, present when Foo is bound and
      // absent otherwise. Mirrors the compiler's IrBinding.CustomWrapper. Wrappers carry no scope.
      val implementationName =
        (returnType.fullyExpandedType as? KaClassType)?.classId?.shortClassName?.asString()
      listOf(
        BindingData(
          optionalTypeKey(returnType, qualifier),
          BindingKind.OPTIONAL,
          null,
          implementationName,
        )
      )
    }
    has(options.multibindsAnnotations) ->
      listOf(
        BindingData(
          typeKey(returnType, qualifier),
          BindingKind.MULTIBINDING_DECLARATION,
          scope,
          null,
        )
      )
    has(options.providesAnnotations) -> {
      val elementType =
        if (has(options.elementsIntoSetAnnotations)) {
          val expanded = returnType.fullyExpandedType as? KaClassType ?: return emptyList()
          if (expanded.classId !in COLLECTION_LIKE_CLASS_IDS) return emptyList()
          expanded.typeArguments.firstOrNull()?.type ?: return emptyList()
        } else {
          returnType
        }
      val elementKey = typeKey(elementType, qualifier)
      val multibindingId = multibindingId(elementKey)
      listOf(
        BindingData(
          elementKey,
          if (multibindingId != null) {
            BindingKind.MULTIBINDING_CONTRIBUTION
          } else {
            BindingKind.PROVIDES
          },
          scope,
          null,
          multibindingId = multibindingId,
        )
      )
    }
    else -> emptyList()
  }
}

private fun KaSession.instanceBindingData(
  parameter: KtParameter,
  options: MetroOptions,
): List<BindingData> {
  val symbol = parameter.symbol as? KaValueParameterSymbol ?: return emptyList()
  if (!symbol.hasAnyAnnotation(options.providesAnnotations)) return emptyList()
  return listOf(
    BindingData(
      typeKey(symbol.returnType, qualifierAnnotation(symbol, options)),
      BindingKind.INSTANCE,
      null,
      null,
    )
  )
}

private fun KaSession.classBindingData(
  ktClass: KtClassOrObject,
  options: MetroOptions,
): List<BindingData> {
  val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return emptyList()
  val result = mutableListOf<BindingData>()
  val qualifier = qualifierAnnotation(classSymbol, options)
  val scope = scopeAnnotation(classSymbol, options)
  val constructors = listOfNotNull(ktClass.primaryConstructor) + ktClass.secondaryConstructors

  fun hasOnClassOrConstructor(classIds: Set<ClassId>): Boolean {
    return classSymbol.hasAnyAnnotation(classIds) ||
      constructors.any { ctor ->
        ctor.symbol.hasAnyAnnotation(classIds)
      }
  }

  val isAssisted = hasOnClassOrConstructor(options.assistedInjectAnnotations)
  val hasInject = hasOnClassOrConstructor(options.injectAnnotations)
  val contributesAnnotations =
    classSymbol.annotations.filter { it.classId in bindingContributionAnnotations(options) }

  // Assisted-injected classes are consumed through their factory, not their own type.
  val isInjectable =
    classSymbol.isInjectableKind() &&
      (hasInject || (options.contributesAsInject && contributesAnnotations.isNotEmpty()))
  val originClassId = ktClass.getClassId()
  if (isInjectable && !isAssisted) {
    result +=
      BindingData(
        typeKey(classSymbol.defaultType, qualifier),
        BindingKind.INJECT,
        scope,
        ktClass.name,
        originClassId = originClassId,
      )
  }

  val intoSetIds =
    options.contributesIntoSetAnnotations + options.customContributesIntoSetAnnotations
  for (annotation in contributesAnnotations) {
    val classId = annotation.classId ?: continue
    val boundType = contributedBoundType(ktClass, classSymbol, annotation) ?: continue
    val elementKey = typeKey(boundType, qualifier)
    val contributionScopes = annotationScopeKeys(annotation)
    val replaces = classListArgument(annotation, "replaces").toSet()
    when (classId) {
      in options.contributesBindingAnnotations ->
        result +=
          BindingData(
            elementKey,
            BindingKind.CONTRIBUTED,
            scope,
            ktClass.name,
            originClassId = originClassId,
            replaces = replaces,
            contributionScopes = contributionScopes,
          )

      in intoSetIds ->
        result +=
          BindingData(
            elementKey,
            BindingKind.MULTIBINDING_CONTRIBUTION,
            scope,
            ktClass.name,
            multibindingId = elementKey.computeMultibindingId(),
            originClassId = originClassId,
            replaces = replaces,
            contributionScopes = contributionScopes,
          )

      in options.contributesIntoMapAnnotations -> {
        val mapKeyType = mapKeyType(classSymbol, options) ?: continue
        result +=
          BindingData(
            elementKey,
            BindingKind.MULTIBINDING_CONTRIBUTION,
            scope,
            ktClass.name,
            multibindingId = createMapBindingId(mapKeyType, elementKey),
            originClassId = originClassId,
            replaces = replaces,
            contributionScopes = contributionScopes,
          )
      }
    }
  }
  return result
}

/**
 * Determines the bound type of a `@ContributesBinding`-style annotation: an explicit `binding<T>()`
 * (or Anvil-interop `boundType`) argument when present, otherwise the sole non-`Any` supertype.
 * Mirrors the compiler's `resolvedBindingArgument`.
 */
private fun KaSession.contributedBoundType(
  ktClass: KtClassOrObject,
  classSymbol: KaNamedClassSymbol,
  annotation: KaAnnotation,
): KaType? {
  // Anvil interop: boundType is a KClass argument, available structurally even from binaries
  val anvilBoundType =
    annotation.arguments
      .firstOrNull { it.name.asString() == "boundType" }
      ?.let { (it.expression as? KaAnnotationValue.ClassLiteralValue)?.type }
  if (anvilBoundType != null) return anvilBoundType

  // Metro's binding<T>() carries the bound type as a *type argument* of a nested annotation,
  // which the Analysis API doesn't expose structurally — read it from PSI. For binary classes
  // KaAnnotation.psi is null, but the decompiled class renders its annotation entries.
  val entryPsi =
    annotation.psi as? KtAnnotationEntry
      ?: ktClass.annotationEntries.firstOrNull {
        it.shortName == annotation.classId?.shortClassName
      }
  val explicitTypeRef =
    entryPsi?.valueArguments?.firstNotNullOfOrNull { argument ->
      val call = argument.getArgumentExpression() as? KtCallExpression
      if (call?.calleeExpression?.text == "binding") {
        call.typeArguments.firstOrNull()?.typeReference
      } else {
        null
      }
    }
  if (explicitTypeRef != null) {
    return explicitTypeRef.type
  }
  val superTypes = classSymbol.superTypes.filterNot { it.isAnyType }
  // A supertype's @DefaultBinding<T> supplies an implicit bound type, checked before the
  // sole-supertype fallback (mirrors ContributionsFirGenerator)
  for (superType in superTypes) {
    val supertypeSymbol =
      (superType.fullyExpandedType as? KaClassType)?.symbol as? KaClassSymbol ?: continue
    resolveDefaultBindingType(supertypeSymbol)?.let {
      return it
    }
  }
  return superTypes.singleOrNull()
}

/**
 * Resolves a supertype's `@DefaultBinding<T>` type argument: from source PSI when available, or
 * from the generated `DefaultBindingMirror.defaultBinding()` return type for binaries (annotation
 * type arguments don't survive into metadata).
 */
private fun KaSession.resolveDefaultBindingType(supertypeSymbol: KaClassSymbol): KaType? {
  val annotation =
    supertypeSymbol.annotations.firstOrNull { it.classId == MetroClassIds.defaultBinding }
      ?: return null
  (annotation.psi as? KtAnnotationEntry)?.typeArguments?.firstOrNull()?.typeReference?.let {
    return it.type
  }
  val mirror =
    supertypeSymbol.declaredMemberScope.classifiers
      .filterIsInstance<KaNamedClassSymbol>()
      .firstOrNull { it.name.asString() == "DefaultBindingMirror" } ?: return null
  return mirror.declaredMemberScope.callables
    .filterIsInstance<KaNamedFunctionSymbol>()
    .firstOrNull { it.name.asString() == "defaultBinding" }
    ?.returnType
}

/** Class-literal list argument values (e.g. `excludes`, `replaces`, `bindingContainers`). */
internal fun classListArgument(annotation: KaAnnotation, name: String): List<ClassId> {
  val argument =
    annotation.arguments.firstOrNull { it.name.asString() == name } ?: return emptyList()
  return when (val value = argument.expression) {
    is KaAnnotationValue.ArrayValue -> value.values.mapNotNull { classLiteralClassId(it) }
    else -> listOfNotNull(classLiteralClassId(value))
  }
}
