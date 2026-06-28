// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.BoundTypeResolution
import dev.zacsweers.metro.compiler.graph.computeMultibindingId
import dev.zacsweers.metro.compiler.graph.createMapBindingId
import dev.zacsweers.metro.compiler.graph.resolveImplicitBoundType
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.classLiteralClassId
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.model.BindingKind
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.qualifierAnnotation
import dev.zacsweers.metro.idea.scopeAnnotation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
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
import org.jetbrains.kotlin.name.StandardClassIds
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

// Dagger interop: `@BindsOptionalOf fun foo(): Foo` makes `java.util.Optional<Foo>` available,
// mirroring the compiler's IrBinding.CustomWrapper. Only active when Dagger runtime interop is on.
private val DAGGER_BINDS_OPTIONAL_OF = ClassId.fromString("dagger/BindsOptionalOf")
internal val JAVA_OPTIONAL_CLASS_ID = ClassId.fromString("java/util/Optional")

internal fun bindsOptionalOfAnnotations(options: MetroOptions): Set<ClassId> {
  return if (options.enableDaggerRuntimeInterop) {
    setOf(DAGGER_BINDS_OPTIONAL_OF)
  } else {
    emptySet()
  }
}

private val COLLECTION_LIKE_CLASS_IDS =
  setOf(
    StandardClassIds.Set,
    StandardClassIds.Collection,
    StandardClassIds.List,
    StandardClassIds.Iterable,
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
  val fieldSymbol = (symbol as? KaPropertySymbol)?.backingFieldSymbol

  fun has(classIds: Set<ClassId>): Boolean {
    return symbol.hasAnyAnnotation(classIds) ||
      getterSymbol?.hasAnyAnnotation(classIds) == true ||
      fieldSymbol?.hasAnyAnnotation(classIds) == true
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
  // The implicit bound type (supertype @DefaultBinding, else sole supertype) is resolved by the
  // shared decision so the IDE and compiler agree on ambiguity. Ambiguous/multiple → unresolved.
  val superTypes = classSymbol.superTypes.filterNot { it.isAnyType }
  val resolution =
    resolveImplicitBoundType(superTypes) { superType ->
      val supertypeSymbol = (superType.fullyExpandedType as? KaClassType)?.symbol as? KaClassSymbol
      supertypeSymbol?.let { resolveDefaultBindingType(it) }
    }
  return when (resolution) {
    is BoundTypeResolution.Resolved -> resolution.type
    else -> null
  }
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
