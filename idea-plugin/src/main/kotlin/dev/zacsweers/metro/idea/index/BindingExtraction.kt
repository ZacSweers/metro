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
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.qualifierAnnotation
import dev.zacsweers.metro.idea.scopeAnnotation
import dev.zacsweers.metro.idea.toKaAnnotationSnapshot
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
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
 * The map key of an `@IntoMap` contribution. [keyTypeRender] identifies the aggregate binding and
 * [annotationRender] carries the key value for duplicate detection.
 */
internal class MapKeyInfo(val keyTypeRender: String, val annotationRender: String?)

/**
 * Resolves the map key of an `@IntoMap` contribution from its map key annotation, mirroring the
 * compiler's `mapKeyType`: the annotation's single member type when the `@MapKey` meta-annotation
 * has `unwrapValue = true` (the default), otherwise the annotation type itself.
 */
internal fun KaSession.mapKeyInfo(annotated: KaAnnotated, options: MetroOptions): MapKeyInfo? {
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
    return MapKeyInfo(
      keyTypeRender = renderKeyType(keyType),
      annotationRender = annotation.toKaAnnotationSnapshot()?.render(short = false),
    )
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

  val mapKeyInfo =
    if (has(options.intoMapAnnotations)) {
      mapKeyInfo(symbol, options) ?: getterSymbol?.let { mapKeyInfo(it, options) }
    } else {
      null
    }

  // Mirrors the compiler's transformIfIntoMultibinding: a contribution keeps its element key as
  // declared and joins its aggregate by id. Ids canonicalize through provider wrappers so `V`,
  // `Provider<V>`, and `() -> V` contributions join the same aggregate as any accessor spelling.
  fun multibindingId(elementKey: KaTypeKey): String? {
    val isIntoMap = has(options.intoMapAnnotations)
    val isElementsIntoSet = has(options.elementsIntoSetAnnotations)
    if (!isIntoMap && !isElementsIntoSet && !has(options.intoSetAnnotations)) return null
    val canonicalKey = contextualTypeKey(returnType, elementKey.qualifier, options).typeKey
    return when {
      isIntoMap -> {
        val mapKeyType = mapKeyInfo?.keyTypeRender ?: return null
        createMapBindingId(mapKeyType, canonicalKey)
      }
      isElementsIntoSet -> {
        // `@ElementsIntoSet fun x(): Collection<X>` contributes X elements
        val elementType = canonicalKey.type.typeArguments.singleOrNull() ?: return null
        canonicalKey.copy(type = elementType).computeMultibindingId()
      }
      else -> canonicalKey.computeMultibindingId()
    }
  }

  return when {
    has(options.bindsAnnotations) -> {
      val sourceType =
        symbol.receiverParameter?.returnType
          ?: (symbol as? KaNamedFunctionSymbol)?.valueParameters?.singleOrNull()?.returnType
          ?: return emptyList()
      val sourceParam = (symbol as? KaNamedFunctionSymbol)?.valueParameters?.singleOrNull()
      val consumedKey =
        contextualTypeKey(
          sourceType,
          sourceParam?.let { qualifierAnnotation(it, options) },
          options,
        )
      val implementationName =
        (sourceType.fullyExpandedType as? KaClassType)?.classId?.shortClassName?.asString()
      val elementKey = typeKey(returnType, qualifier)
      val multibindingId = multibindingId(elementKey)
      listOf(
        BindingData(
          elementKey,
          BindingData.Kind.ALIAS,
          scope,
          implementationName,
          consumedKey,
          multibindingId,
          mapKeyValue = mapKeyInfo?.annotationRender,
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
          BindingData.Kind.CUSTOM_WRAPPER,
          null,
          implementationName,
        )
      )
    }
    has(options.multibindsAnnotations) -> {
      val allowEmpty =
        (symbol.annotations + listOfNotNull(getterSymbol).flatMap { it.annotations })
          .firstOrNull { it.classId in options.multibindsAnnotations }
          ?.arguments
          ?.firstOrNull { it.name.asString() == "allowEmpty" }
          ?.let { (it.expression as? KaAnnotationValue.ConstantValue)?.value?.value } == true
      listOf(
        BindingData(
          typeKey(returnType, qualifier),
          BindingData.Kind.MULTIBINDING,
          scope,
          null,
          allowEmpty = allowEmpty,
        )
      )
    }
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
      // Extension receivers on provider callables are dependencies, same as value parameters.
      val receiverDependency = symbol.receiverParameter?.let { dependencyKey(it, options) }
      val dependencies =
        listOfNotNull(receiverDependency) +
          (symbol as? KaNamedFunctionSymbol)
            ?.valueParameters
            .orEmpty()
            .filterNot { it.hasAnyAnnotation(options.assistedAnnotations) }
            .map { dependencyKey(it, options) }
      listOf(
        BindingData(
          elementKey,
          BindingData.Kind.PROVIDED,
          scope,
          null,
          multibindingId = multibindingId,
          dependencies = dependencies,
          mapKeyValue = mapKeyInfo?.annotationRender,
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
      BindingData.Kind.BOUND_INSTANCE,
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
  val ownsInjectBinding = isInjectable && !isAssisted
  if (ownsInjectBinding) {
    result +=
      BindingData(
        typeKey(classSymbol.defaultType, qualifier),
        BindingData.Kind.CONSTRUCTOR_INJECTED,
        scope,
        ktClass.name,
        originClassId = originClassId,
        dependencies = injectClassDependencyKeys(classSymbol, options),
      )
  }
  // Contributed bindings alias the class's own inject binding, matching the compiler's model.
  // No alias edge when the class originates no own-type binding.
  val consumedKey =
    if (ownsInjectBinding) {
      contextualTypeKey(classSymbol.defaultType, qualifier, options)
    } else {
      null
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
            BindingData.Kind.ALIAS,
            scope,
            ktClass.name,
            consumedKey = consumedKey,
            originClassId = originClassId,
            replaces = replaces,
            contributionScopes = contributionScopes,
            isClassContribution = true,
          )

      in intoSetIds ->
        result +=
          BindingData(
            elementKey,
            BindingData.Kind.ALIAS,
            scope,
            ktClass.name,
            consumedKey = consumedKey,
            multibindingId = elementKey.computeMultibindingId(),
            originClassId = originClassId,
            replaces = replaces,
            contributionScopes = contributionScopes,
            isClassContribution = true,
          )

      in options.contributesIntoMapAnnotations -> {
        val mapKeyInfo = mapKeyInfo(classSymbol, options) ?: continue
        result +=
          BindingData(
            elementKey,
            BindingData.Kind.ALIAS,
            scope,
            ktClass.name,
            consumedKey = consumedKey,
            multibindingId = createMapBindingId(mapKeyInfo.keyTypeRender, elementKey),
            originClassId = originClassId,
            replaces = replaces,
            contributionScopes = contributionScopes,
            mapKeyValue = mapKeyInfo.annotationRender,
            isClassContribution = true,
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

/**
 * Resolves the constructor Metro injects for [classSymbol]. Works for both source and library
 * classes.
 */
internal fun KaSession.findInjectConstructorSymbol(
  classSymbol: KaNamedClassSymbol,
  options: MetroOptions,
): KaConstructorSymbol? {
  if (!classSymbol.isInjectableKind()) return null
  val injectish = options.allInjectAnnotations
  val classLevel =
    classSymbol.hasAnyAnnotation(injectish) ||
      (options.contributesAsInject &&
        classSymbol.annotations.any { it.classId in bindingContributionAnnotations(options) })
  val constructors = classSymbol.memberScope.constructors.toList()
  val annotatedConstructor = constructors.firstOrNull { it.hasAnyAnnotation(injectish) }
  if (annotatedConstructor != null) return annotatedConstructor
  return if (classLevel) constructors.firstOrNull { it.isPrimary } else null
}

/**
 * The dependency keys of [classSymbol]'s inject constructor. `@Assisted` parameters are excluded
 * because they are supplied at creation time, not by the graph.
 */
internal fun KaSession.injectConstructorDependencyKeys(
  classSymbol: KaNamedClassSymbol,
  options: MetroOptions,
): List<KaContextualTypeKey> {
  val constructor = findInjectConstructorSymbol(classSymbol, options) ?: return emptyList()
  return constructor.valueParameters
    .filterNot { it.hasAnyAnnotation(options.assistedAnnotations) }
    .map { dependencyKey(it, options) }
}

/**
 * The dependency keys of [classSymbol]'s member injection sites. Superclasses are only checked when
 * annotated with `@HasMemberInjections`, which Metro requires for inherited member injections.
 */
internal fun KaSession.memberInjectDependencyKeys(
  classSymbol: KaNamedClassSymbol,
  options: MetroOptions,
): List<KaContextualTypeKey> {
  val result = mutableListOf<KaContextualTypeKey>()
  var current: KaNamedClassSymbol? = classSymbol
  while (current != null) {
    collectDeclaredMemberInjectKeys(current, options, result)
    current =
      superClassSymbol(current)?.takeIf {
        it.hasAnyAnnotation(setOf(MetroClassIds.hasMemberInjections))
      }
  }
  return result
}

private fun KaSession.collectDeclaredMemberInjectKeys(
  classSymbol: KaNamedClassSymbol,
  options: MetroOptions,
  result: MutableList<KaContextualTypeKey>,
) {
  val injectIds = options.allInjectAnnotations
  for (callable in classSymbol.declaredMemberScope.callables) {
    when (callable) {
      is KaPropertySymbol -> {
        // @Inject has no PROPERTY target. A bare annotation lands on the backing field.
        val injected =
          callable.hasAnyAnnotation(injectIds) ||
            callable.backingFieldSymbol?.hasAnyAnnotation(injectIds) == true ||
            callable.setter?.hasAnyAnnotation(injectIds) == true
        if (injected) {
          result += dependencyKey(callable, options)
        }
      }
      is KaNamedFunctionSymbol ->
        if (callable.hasAnyAnnotation(injectIds)) {
          callable.valueParameters.mapTo(result) { dependencyKey(it, options) }
        }
      else -> {}
    }
  }
}

private fun KaSession.superClassSymbol(classSymbol: KaNamedClassSymbol): KaNamedClassSymbol? {
  for (superType in classSymbol.superTypes) {
    val symbol =
      (superType.fullyExpandedType as? KaClassType)?.symbol as? KaNamedClassSymbol ?: continue
    if (symbol.classKind == KaClassKind.CLASS) return symbol
  }
  return null
}

/** Every key the graph must provide to construct and member-inject [classSymbol]. */
internal fun KaSession.injectClassDependencyKeys(
  classSymbol: KaNamedClassSymbol,
  options: MetroOptions,
): List<KaContextualTypeKey> {
  return injectConstructorDependencyKeys(classSymbol, options) +
    memberInjectDependencyKeys(classSymbol, options)
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
