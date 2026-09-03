// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.BoundTypeResolution
import dev.zacsweers.metro.compiler.graph.computeMultibindingId
import dev.zacsweers.metro.compiler.graph.createMapBindingId
import dev.zacsweers.metro.compiler.graph.resolveImplicitBoundType
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.checkCanceledEvery
import dev.zacsweers.metro.idea.classLiteralClassId
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaAnnotationValueSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.qualifierAnnotation
import dev.zacsweers.metro.idea.scopeAnnotation
import dev.zacsweers.metro.idea.toKaAnnotationSnapshot
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.signatures.KaFunctionSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
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

/** The original annotation value and whether it needs the consuming graph's Anvil interop. */
internal data class ExtractedPriority(val value: Int, val fromAnvilRank: Boolean)

internal fun KaAnnotation.priority(): ExtractedPriority {
  fun intArgument(name: String): Int? {
    val value =
      arguments
        .firstOrNull { it.name.asString() == name }
        ?.let { (it.expression as? KaAnnotationValue.ConstantValue)?.value?.value }
    return value as? Int
  }

  val priority = intArgument("priority")
  if (priority != null) return ExtractedPriority(priority, fromAnvilRank = false)

  val rank = intArgument("rank")
  if (rank != null) return ExtractedPriority(rank, fromAnvilRank = true)

  return ExtractedPriority(Int.MIN_VALUE, fromAnvilRank = false)
}

/** Kotlin Inject Anvil routes binding contributions with this flag into a set instead. */
internal fun KaAnnotation.isMultibindingContribution(): Boolean {
  return booleanArgument("multibinding")
}

/** Anvil contributions can suppress the qualifier declared on their implementation class. */
private fun KaAnnotation.ignoresContributionQualifier(): Boolean {
  return booleanArgument("ignoreQualifier")
}

private fun KaAnnotation.booleanArgument(name: String): Boolean {
  val argument = arguments.firstOrNull { it.name.asString() == name } ?: return false
  val value = (argument.expression as? KaAnnotationValue.ConstantValue)?.value?.value
  return value == true
}

internal fun nonAccessorCallableAnnotations(options: MetroOptions): Set<ClassId> {
  return buildSet {
    addAll(options.bindsAnnotations)
    addAll(options.providesAnnotations)
    addAll(options.multibindsAnnotations)
  }
}

/** Only ordinary contributed interfaces become generated graph supertypes. */
internal fun KaNamedClassSymbol.contributionKind(options: MetroOptions): ContributionEntry.Kind {
  if (hasAnyAnnotation(options.bindingContainerAnnotations)) {
    return ContributionEntry.Kind.BINDING_CONTAINER
  }
  if (classKind == KaClassKind.INTERFACE && hasAnyAnnotation(options.contributesToAnnotations)) {
    return ContributionEntry.Kind.GRAPH_INTERFACE
  }
  return ContributionEntry.Kind.OTHER
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
 * The map key of an `@IntoMap` contribution. [keyTypeRender] identifies the multibinding and
 * [annotationRender] carries the key value for duplicate detection.
 */
internal class MapKeyInfo(val keyTypeRender: String, val annotationRender: String?)

/** A callable parameter paired with its use-site-substituted type. */
internal class CallableParameterView(val symbol: KaCallableSymbol, val returnType: KaType)

/** A callable declaration paired with the types substituted for one concrete receiver type. */
internal class CallableBindingView(
  val symbol: KaCallableSymbol,
  val returnType: KaType,
  val receiver: CallableParameterView?,
  val valueParameters: List<CallableParameterView>,
)

internal fun callableBindingView(symbol: KaCallableSymbol): CallableBindingView {
  val receiver = symbol.receiverParameter?.let { CallableParameterView(it, it.returnType) }
  val valueParameters =
    (symbol as? KaNamedFunctionSymbol)?.valueParameters.orEmpty().map {
      CallableParameterView(it, it.returnType)
    }
  return CallableBindingView(symbol, symbol.returnType, receiver, valueParameters)
}

/** Unwraps fake overrides for source metadata while retaining [signature]'s substituted types. */
internal fun KaSession.callableBindingView(
  signature: KaCallableSignature<*>
): CallableBindingView? {
  val sourceSymbol = signature.symbol.fakeOverrideOriginal
  val sourceParameters = (sourceSymbol as? KaNamedFunctionSymbol)?.valueParameters.orEmpty()
  val signatureParameters = (signature as? KaFunctionSignature<*>)?.valueParameters.orEmpty()
  if (sourceParameters.size != signatureParameters.size) return null

  val receiver =
    sourceSymbol.receiverParameter?.let { sourceReceiver ->
      val receiverType = signature.receiverType ?: return@let null
      CallableParameterView(sourceReceiver, receiverType)
    }
  val valueParameters = signatureParameters.mapIndexed { index, parameter ->
    CallableParameterView(sourceParameters[index], parameter.returnType)
  }
  return CallableBindingView(sourceSymbol, signature.returnType, receiver, valueParameters)
}

/** Resolves an assisted factory's SAM for the concrete type requested by its graph. */
internal fun KaSession.assistedFactoryFunction(factoryType: KaClassType): CallableBindingView? {
  val scope = factoryType.scope ?: return null
  var signature: KaFunctionSignature<*>? = null
  for ((index, candidate) in scope.getCallableSignatures().withIndex()) {
    checkCanceledEvery(index)
    if (candidate !is KaFunctionSignature<*>) continue
    val symbol = candidate.symbol
    if (symbol !is KaNamedFunctionSymbol || symbol.modality != KaSymbolModality.ABSTRACT) continue
    if (signature != null) return null
    signature = candidate
  }
  val factorySignature = signature ?: return null
  return callableBindingView(factorySignature)
}

/**
 * Resolves the map key of an `@IntoMap` contribution from its map key annotation, mirroring the
 * compiler's `mapKeyType`: the annotation's single member type when the `@MapKey` meta-annotation
 * has `unwrapValue = true` (the default), otherwise the annotation type itself. Class contributions
 * also resolve implicit `Nothing::class` keys to their actual implementation.
 */
internal fun KaSession.mapKeyInfo(
  annotated: KaAnnotated,
  options: MetroOptions,
  implicitClassId: ClassId? = null,
): MapKeyInfo? {
  for ((index, annotation) in annotated.annotations.withIndex()) {
    checkCanceledEvery(index)
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
      annotationRender =
        toKaAnnotationSnapshot(annotation)
          ?.resolveImplicitClassKey(mapKeyMeta, implicitClassId)
          ?.render(short = false),
    )
  }
  return null
}

private fun KaAnnotationSnapshot.resolveImplicitClassKey(
  mapKeyMeta: KaAnnotation,
  implicitClassId: ClassId?,
): KaAnnotationSnapshot {
  if (implicitClassId == null) return this

  val usesImplicitClassKey =
    mapKeyMeta.arguments
      .firstOrNull { it.name.asString() == "implicitClassKey" }
      ?.let { (it.expression as? KaAnnotationValue.ConstantValue)?.value?.value } == true
  if (!usesImplicitClassKey) return this

  val valueArgument = arguments.firstOrNull { it.first.asString() == "value" }
  val existingClassReference = valueArgument?.second as? KaAnnotationValueSnapshot.KClassRef
  val hasExplicitClassKey =
    valueArgument != null && existingClassReference?.classId != StandardClassIds.Nothing
  if (hasExplicitClassKey) return this

  val resolvedClassReference = KaAnnotationValueSnapshot.KClassRef(implicitClassId)
  val resolvedArguments =
    if (valueArgument == null) {
      arguments + (Name.identifier("value") to resolvedClassReference)
    } else {
      arguments.map { argument ->
        if (argument.first.asString() == "value") {
          argument.first to resolvedClassReference
        } else {
          argument
        }
      }
    }
  return copy(arguments = resolvedArguments)
}

/**
 * Computes the bindings originated by this declaration: `@Provides`/`@Binds`/`@Multibinds`
 * callables, injected classes, contributed bindings, and instance-binding factory parameters.
 * [onDeclarationFile] records files read for inherited member injection metadata.
 */
internal fun KtDeclaration.bindingData(
  session: KaSession,
  options: MetroOptions,
  onDeclarationFile: ((PsiFile) -> Unit)? = null,
): List<BindingData> {
  return when (this) {
    is KtPropertyAccessor -> property.bindingData(session, options)
    is KtNamedFunction,
    is KtProperty -> (this as KtCallableDeclaration).callableBindingData(session, options)
    is KtParameter -> instanceBindingData(session, options)
    is KtClassOrObject -> classBindingData(session, options, onDeclarationFile)
    is KtConstructor<*> ->
      getContainingClassOrObject().classBindingData(session, options, onDeclarationFile)
    else -> emptyList()
  }
}

private fun KtCallableDeclaration.callableBindingData(
  session: KaSession,
  options: MetroOptions,
): List<BindingData> =
  with(session) {
    val symbol = this@callableBindingData.symbol as? KaCallableSymbol ?: return@with emptyList()
    callableBindingView(symbol).bindingData(session, options)
  }

/** Computes callable binding data from declaration metadata and use-site-substituted types. */
internal fun CallableBindingView.bindingData(
  session: KaSession,
  options: MetroOptions,
): List<BindingData> =
  with(session) {
    val callable = this@bindingData
    val symbol = callable.symbol
    val getterSymbol = (symbol as? KaPropertySymbol)?.getter
    val fieldSymbol = (symbol as? KaPropertySymbol)?.backingFieldSymbol

    fun has(classIds: Set<ClassId>): Boolean {
      return symbol.hasAnyAnnotation(classIds) ||
        getterSymbol?.hasAnyAnnotation(classIds) == true ||
        fieldSymbol?.hasAnyAnnotation(classIds) == true
    }

    val qualifier = qualifierAnnotation(symbol, options)
    val scope = scopeAnnotation(symbol, options)
    val returnType = callable.returnType
    val isGraphPrivate =
      symbol.annotations.any { it.classId == MetroClassIds.graphPrivate } ||
        getterSymbol?.annotations?.any { it.classId == MetroClassIds.graphPrivate } == true ||
        fieldSymbol?.annotations?.any { it.classId == MetroClassIds.graphPrivate } == true

    val mapKeyInfo =
      if (has(options.intoMapAnnotations)) {
        mapKeyInfo(symbol, options) ?: getterSymbol?.let { mapKeyInfo(it, options) }
      } else {
        null
      }

    // Mirrors the compiler's transformIfIntoMultibinding: a contribution keeps its element key as
    // declared and joins its multibinding by id. Ids canonicalize through provider wrappers so `V`,
    // `Provider<V>`, and `() -> V` contributions join the same multibinding as any accessor
    // spelling.
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
          val elementType = canonicalKey.type.typeArguments.singleOrNull()?.type ?: return null
          canonicalKey.copy(type = elementType).computeMultibindingId()
        }
        else -> canonicalKey.computeMultibindingId()
      }
    }

    when {
      has(options.bindsAnnotations) -> {
        val source = callable.receiver ?: callable.valueParameters.singleOrNull()
        if (source == null) return@with emptyList()
        val sourceType = source.returnType
        val consumedKey =
          contextualTypeKey(sourceType, qualifierAnnotation(source.symbol, options), options)
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
            isGraphPrivate = isGraphPrivate,
          )
        )
      }
      has(bindsOptionalOfAnnotations(options)) -> {
        // `@BindsOptionalOf fun foo(): Foo` exposes `Optional<Foo>`, present when Foo is bound and
        // absent otherwise. Mirrors the compiler's IrBinding.CustomWrapper. Wrappers carry no
        // scope.
        val implementationName =
          (returnType.fullyExpandedType as? KaClassType)?.classId?.shortClassName?.asString()
        val wrappedContextKey = contextualTypeKey(returnType, qualifier, options).withDefault(true)
        listOf(
          BindingData(
            optionalTypeKey(returnType, qualifier),
            BindingData.Kind.CUSTOM_WRAPPER,
            null,
            implementationName,
            dependencies = listOf(wrappedContextKey),
            isGraphPrivate = isGraphPrivate,
          )
        )
      }
      has(options.multibindsAnnotations) -> {
        val annotations =
          (symbol.annotations + listOfNotNull(getterSymbol).flatMap { it.annotations }).filter {
            it.classId in options.multibindsAnnotations
          }
        val allowEmpty =
          annotations
            .firstOrNull()
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
            metroMultibindsAnnotation = sourceMetroMultibindsAnnotation(annotations),
            isGraphPrivate = isGraphPrivate,
          )
        )
      }
      has(options.providesAnnotations) -> {
        val elementType =
          if (has(options.elementsIntoSetAnnotations)) {
            val expanded = returnType.fullyExpandedType as? KaClassType ?: return@with emptyList()
            if (expanded.classId !in COLLECTION_LIKE_CLASS_IDS) return@with emptyList()
            expanded.typeArguments.firstOrNull()?.type ?: return@with emptyList()
          } else {
            returnType
          }
        val elementKey = typeKey(elementType, qualifier)
        val multibindingId = multibindingId(elementKey)
        // Extension receivers on provider callables are dependencies, same as value parameters.
        val receiverDependency =
          callable.receiver?.let { dependencyKey(it.returnType, it.symbol, options) }
        val dependencies =
          listOfNotNull(receiverDependency) +
            callable.valueParameters
              .filterIndexed { index, parameter ->
                checkCanceledEvery(index)
                !parameter.symbol.hasAnyAnnotation(options.assistedAnnotations)
              }
              .map { dependencyKey(it.returnType, it.symbol, options) }
        listOf(
          BindingData(
            elementKey,
            BindingData.Kind.PROVIDED,
            scope,
            null,
            multibindingId = multibindingId,
            dependencies = dependencies,
            isSuspend = (symbol as? KaNamedFunctionSymbol)?.isSuspend == true,
            mapKeyValue = mapKeyInfo?.annotationRender,
            isGraphPrivate = isGraphPrivate,
          )
        )
      }
      else -> emptyList()
    }
  }

private fun KtParameter.instanceBindingData(
  session: KaSession,
  options: MetroOptions,
): List<BindingData> =
  with(session) {
    val symbol =
      this@instanceBindingData.symbol as? KaValueParameterSymbol ?: return@with emptyList()
    CallableParameterView(symbol, symbol.returnType).instanceBindingData(this, options)
  }

private fun KtClassOrObject.classBindingData(
  session: KaSession,
  options: MetroOptions,
  onDeclarationFile: ((PsiFile) -> Unit)?,
): List<BindingData> =
  with(session) {
    val ktClass = this@classBindingData
    val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@with emptyList()
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

    val isInjectable =
      classSymbol.isInjectableKind() &&
        (hasInject ||
          isAssisted ||
          (options.contributesAsInject && contributesAnnotations.isNotEmpty()))
    val originClassId = ktClass.getClassId()
    val injectConstructor =
      if (isInjectable) findInjectConstructorSymbol(classSymbol, options) else null
    val hasPrivateInjectConstructor =
      (injectConstructor?.psi as? KtConstructor<*>)?.hasModifier(KtTokens.PRIVATE_KEYWORD) == true
    val isAssistedFactory = classSymbol.hasAnyAnnotation(options.assistedFactoryAnnotations)
    val usesContributionProvider =
      options.generateContributionProviders &&
        classSymbol.origin != KaSymbolOrigin.LIBRARY &&
        contributesAnnotations.isNotEmpty() &&
        !isAssistedFactory &&
        !classSymbol.hasAnyAnnotation(options.contributionProviderExclusionAnnotations) &&
        !hasPrivateInjectConstructor
    val ownsInjectBinding = isInjectable && !usesContributionProvider
    val needsConstructorMetadata = isInjectable && (ownsInjectBinding || usesContributionProvider)
    val constructorDependencies =
      if (needsConstructorMetadata) {
        injectConstructorDependencyKeys(classSymbol, options, injectConstructor)
      } else {
        emptyList()
      }
    val memberDependencies =
      if (ownsInjectBinding) {
        memberInjectDependencyKeys(classSymbol, options, onDeclarationFile)
      } else {
        emptyList()
      }
    val memberInjectionOwnerIds =
      if (ownsInjectBinding) memberInjectOwnerClassIds(classSymbol) else emptySet()
    if (ownsInjectBinding) {
      result +=
        BindingData(
          typeKey(classSymbol.defaultType, qualifier),
          BindingData.Kind.CONSTRUCTOR_INJECTED,
          scope,
          ktClass.name,
          originClassId = originClassId,
          constructorDependencies = constructorDependencies,
          memberDependencies = memberDependencies,
          memberInjectionOwnerIds = memberInjectionOwnerIds,
          isAssisted = isAssisted,
        )
    }
    // Normal contributions alias the implementation. Generated contribution providers call its
    // constructor directly, so they neither expose its own type nor perform member injection.
    val consumedKey =
      if (ownsInjectBinding || isAssistedFactory) {
        contextualTypeKey(classSymbol.defaultType, qualifier, options)
      } else {
        null
      }

    val intoSetIds =
      options.contributesIntoSetAnnotations + options.customContributesIntoSetAnnotations
    for ((index, annotation) in contributesAnnotations.withIndex()) {
      checkCanceledEvery(index)
      val classId = annotation.classId ?: continue
      val boundType = contributedBoundType(ktClass, classSymbol, annotation) ?: continue
      val annotatedBoundType = boundType as? KaAnnotated
      val boundTypeQualifier = annotatedBoundType?.let { qualifierAnnotation(it, options) }
      val contributionQualifier =
        if (annotation.ignoresContributionQualifier()) {
          null
        } else {
          boundTypeQualifier ?: qualifier
        }
      val elementType = unannotatedBoundType(boundType)
      val elementKey = typeKey(elementType, contributionQualifier)
      val contributionScopes = annotationScopeKeys(annotation)
      val replaces = classListArgument(annotation, "replaces").toSet()
      val bindingKind =
        if (usesContributionProvider) BindingData.Kind.PROVIDED else BindingData.Kind.ALIAS
      val providerDependencies =
        if (usesContributionProvider) constructorDependencies else emptyList()
      val isBindingAnnotation = classId in options.contributesBindingAnnotations
      val isMultibindingBindingContribution =
        isBindingAnnotation && annotation.isMultibindingContribution()
      val isCustomSetContribution = classId in options.customContributesIntoSetAnnotations
      val isMapAnnotation = classId in options.contributesIntoMapAnnotations
      val classMapKeyInfo =
        if (isCustomSetContribution || isMapAnnotation) {
          mapKeyInfo(classSymbol, options, originClassId)
        } else {
          null
        }
      val contributionMapKeyInfo =
        if (isMapAnnotation) {
          val typeMapKeyInfo = annotatedBoundType?.let { mapKeyInfo(it, options, originClassId) }
          typeMapKeyInfo ?: classMapKeyInfo
        } else {
          classMapKeyInfo
        }
      val isKeyedCustomSetContribution = isCustomSetContribution && classMapKeyInfo != null
      val isMapContribution = isMapAnnotation || isKeyedCustomSetContribution
      when {
        isBindingAnnotation && !isMultibindingBindingContribution -> {
          val priority = annotation.priority()
          result +=
            BindingData(
              key = elementKey,
              kind = bindingKind,
              scope = scope,
              implementationName = ktClass.name,
              consumedKey = consumedKey,
              originClassId = originClassId,
              replaces = replaces,
              contributionScopes = contributionScopes,
              priority = priority.value,
              priorityFromAnvilRank = priority.fromAnvilRank,
              dependencies = providerDependencies,
              memberInjectionOwnerIds = memberInjectionOwnerIds,
              isClassContribution = true,
            )
        }

        isMapContribution -> {
          val mapKeyInfo = contributionMapKeyInfo ?: continue
          val priority = annotation.priority()
          result +=
            BindingData(
              key = elementKey,
              kind = bindingKind,
              scope = scope,
              implementationName = ktClass.name,
              consumedKey = consumedKey,
              multibindingId = createMapBindingId(mapKeyInfo.keyTypeRender, elementKey),
              originClassId = originClassId,
              replaces = replaces,
              contributionScopes = contributionScopes,
              priority = priority.value,
              priorityFromAnvilRank = priority.fromAnvilRank,
              dependencies = providerDependencies,
              memberInjectionOwnerIds = memberInjectionOwnerIds,
              mapKeyValue = mapKeyInfo.annotationRender,
              isClassContribution = true,
            )
        }

        classId in intoSetIds || isMultibindingBindingContribution ->
          result +=
            BindingData(
              key = elementKey,
              kind = bindingKind,
              scope = scope,
              implementationName = ktClass.name,
              consumedKey = consumedKey,
              multibindingId = elementKey.computeMultibindingId(),
              originClassId = originClassId,
              replaces = replaces,
              contributionScopes = contributionScopes,
              dependencies = providerDependencies,
              memberInjectionOwnerIds = memberInjectionOwnerIds,
              isClassContribution = true,
            )
      }
    }
    result
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
  // which the Analysis API doesn't expose structurally, so read it from PSI. For binary classes
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
  return when (val resolution = implicitContributedBoundType(classSymbol)) {
    is BoundTypeResolution.Resolved -> resolution.type
    else -> null
  }
}

/** Keeps contribution indexing and source actions on the same implicit bound-type decision. */
internal fun KaSession.implicitContributedBoundType(
  classSymbol: KaNamedClassSymbol
): BoundTypeResolution<KaType> {
  // The implicit bound type comes from a supertype's @DefaultBinding or the sole supertype.
  // The shared decision preserves ambiguous choices for the caller to handle.
  val superTypes =
    classSymbol.superTypes.filterIndexed { index, type ->
      checkCanceledEvery(index)
      !type.isAnyType
    }
  return resolveImplicitBoundType(superTypes) { superType ->
    val supertypeSymbol = (superType.fullyExpandedType as? KaClassType)?.symbol as? KaClassSymbol
    supertypeSymbol?.let { resolveDefaultBindingType(it) }
  }
}

/** Retains an edit target only when the declaration has one resolved Metro source annotation. */
private fun sourceMetroMultibindsAnnotation(
  annotations: List<KaAnnotation>
): SmartPsiElementPointer<KtAnnotationEntry>? {
  val annotation = annotations.distinctBy { it.psi ?: it }.singleOrNull() ?: return null
  if (annotation.classId != MetroClassIds.multibinds) return null
  val entry = annotation.psi as? KtAnnotationEntry ?: return null
  val file = entry.containingFile as? KtFile ?: return null
  if (file.isCompiled) return null
  val virtualFile = file.virtualFile ?: return null
  if (!ProjectFileIndex.getInstance(entry.project).isInSourceContent(virtualFile)) return null
  return SmartPointerManager.createPointer(entry)
}

/** Removes outer type annotations without replacing the declared projections or nullability. */
private fun KaSession.unannotatedBoundType(boundType: KaType): KaType {
  val annotatedType = boundType as? KaAnnotated ?: return boundType
  if (annotatedType.annotations.isEmpty()) return boundType

  val classType = boundType.fullyExpandedType as? KaClassType ?: return boundType
  return buildClassType(classType.classId) {
    isMarkedNullable = classType.isMarkedNullable
    for ((index, projection) in classType.typeArguments.withIndex()) {
      checkCanceledEvery(index)
      argument(projection)
    }
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
      .withIndex()
      .onEach { checkCanceledEvery(it.index) }
      .map { it.value }
      .filterIsInstance<KaNamedClassSymbol>()
      .firstOrNull { it.name.asString() == "DefaultBindingMirror" } ?: return null
  return mirror.declaredMemberScope.callables
    .withIndex()
    .onEach { checkCanceledEvery(it.index) }
    .map { it.value }
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
  val classLevel = hasClassLevelInject(classSymbol, options)
  val constructors = classSymbol.memberScope.constructors.toList()
  val annotatedConstructor =
    constructors.withIndex().firstNotNullOfOrNull { (index, constructor) ->
      checkCanceledEvery(index)
      constructor.takeIf { it.hasAnyAnnotation(options.allInjectAnnotations) }
    }
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
  constructor: KaConstructorSymbol? = findInjectConstructorSymbol(classSymbol, options),
): List<KaContextualTypeKey> {
  return constructor
    ?.valueParameters
    .orEmpty()
    .filterIndexed { index, parameter ->
      checkCanceledEvery(index)
      !parameter.hasAnyAnnotation(options.assistedAnnotations)
    }
    .map { dependencyKey(it, options) }
}

/**
 * Builds one assisted-factory binding without following its factory-typed dependencies. The factory
 * constructs its target directly, so dependencies use the concrete requested type arguments rather
 * than the target class's unspecialized default type.
 */
internal fun KaSession.assistedFactoryBinding(
  classSymbol: KaNamedClassSymbol,
  factoryType: KaClassType,
  options: MetroOptions,
  pointerManager: SmartPointerManager,
  factoryKey: KaTypeKey = typeKey(factoryType, qualifierAnnotation(classSymbol, options)),
  onDependencyType: ((KaType) -> Unit)? = null,
  onDeclarationFile: ((PsiFile) -> Unit)? = null,
): KaBinding.AssistedFactory? {
  val declaration = classSymbol.psi ?: return null
  declaration.containingFile?.let { onDeclarationFile?.invoke(it) }
  val factoryFunction = assistedFactoryFunction(factoryType)
  val samFunction = factoryFunction?.symbol as? KaNamedFunctionSymbol
  samFunction?.psi?.containingFile?.let { onDeclarationFile?.invoke(it) }
  val targetType = factoryFunction?.returnType?.fullyExpandedType as? KaClassType
  val targetSymbol = targetType?.symbol as? KaNamedClassSymbol
  targetSymbol?.psi?.containingFile?.let { onDeclarationFile?.invoke(it) }
  return KaBinding.AssistedFactory(
    pointerManager.createSmartPsiElementPointer(declaration),
    factoryKey,
    scopeAnnotation(classSymbol, options),
    targetSymbol?.classId?.shortClassName?.asString(),
    targetType?.let { typeKey(it, qualifier = null) },
    originClassId = classSymbol.classId,
    targetConstructorDependencies =
      targetType?.let { injectConstructorDependencyKeys(it, options, onDependencyType) }.orEmpty(),
    targetMemberDependencies =
      targetType
        ?.let { memberInjectDependencyKeys(it, options, onDeclarationFile, onDependencyType) }
        .orEmpty(),
    memberInjectionOwnerIds = targetSymbol?.let { memberInjectOwnerClassIds(it) }.orEmpty(),
    factoryFunctionName = samFunction?.name?.asString(),
    factoryFunctionIsSuspend = samFunction?.isSuspend == true,
  )
}

/** Resolves constructor dependencies with the concrete arguments of an assisted factory target. */
@OptIn(KaExperimentalApi::class)
internal fun KaSession.injectConstructorDependencyKeys(
  classType: KaClassType,
  options: MetroOptions,
  onDependencyType: ((KaType) -> Unit)? = null,
): List<KaContextualTypeKey> {
  val classSymbol = classType.symbol as? KaNamedClassSymbol ?: return emptyList()
  val constructor = findInjectConstructorSymbol(classSymbol, options) ?: return emptyList()
  val typeParameters = classSymbol.typeParameters
  if (typeParameters.isEmpty() && onDependencyType == null) {
    return injectConstructorDependencyKeys(classSymbol, options, constructor)
  }

  val substitutions = typeParameters.mapIndexedNotNull { index, parameter ->
    checkCanceledEvery(index)
    val argument = classType.typeArguments.getOrNull(index)?.type ?: return@mapIndexedNotNull null
    parameter to argument
  }
  if (substitutions.isEmpty() && onDependencyType == null) {
    return injectConstructorDependencyKeys(classSymbol, options, constructor)
  }

  val substitutor = if (substitutions.isEmpty()) null else createSubstitutor(substitutions.toMap())
  return constructor.valueParameters
    .filterIndexed { index, parameter ->
      checkCanceledEvery(index)
      !parameter.hasAnyAnnotation(options.assistedAnnotations)
    }
    .map { parameter ->
      val dependencyType = substitutor?.substitute(parameter.returnType) ?: parameter.returnType
      onDependencyType?.invoke(dependencyType)
      dependencyKey(dependencyType, parameter, options)
    }
}

/**
 * The dependency keys of [classSymbol]'s member injection sites. Superclasses are only checked when
 * annotated with `@HasMemberInjections`, which Metro requires for inherited member injections.
 */
internal class MemberInjectSite(
  val ownerClassId: ClassId?,
  val declaration: KtElement?,
  val key: KaContextualTypeKey,
)

internal fun KaSession.memberInjectDependencyKeys(
  classSymbol: KaNamedClassSymbol,
  options: MetroOptions,
  onDeclarationFile: ((PsiFile) -> Unit)? = null,
): List<KaContextualTypeKey> {
  return memberInjectSites(classSymbol, options, onDeclarationFile).map { it.key }
}

/** Resolves direct and inherited member injections through the target's specialized type scope. */
internal fun KaSession.memberInjectDependencyKeys(
  classType: KaClassType,
  options: MetroOptions,
  onDeclarationFile: ((PsiFile) -> Unit)? = null,
  onDependencyType: ((KaType) -> Unit)? = null,
): List<KaContextualTypeKey> {
  return memberInjectSites(classType, options, onDeclarationFile, onDependencyType).map { it.key }
}

/** Preserves member source locations while specializing direct and inherited injection sites. */
internal fun KaSession.memberInjectSites(
  classType: KaClassType,
  options: MetroOptions,
  onDeclarationFile: ((PsiFile) -> Unit)? = null,
  onDependencyType: ((KaType) -> Unit)? = null,
): List<MemberInjectSite> {
  val classSymbol = classType.symbol as? KaNamedClassSymbol ?: return emptyList()
  val owners = memberInjectOwners(classSymbol, onDeclarationFile)
  if (
    classSymbol.typeParameters.isEmpty() &&
      classType.typeArguments.isEmpty() &&
      owners.size == 1 &&
      onDependencyType == null
  ) {
    return memberInjectSites(classSymbol, options)
  }

  val scope = classType.scope ?: return memberInjectSites(classSymbol, options)
  val ownerIds = owners.mapNotNullTo(linkedSetOf()) { it.classId }
  val result = mutableListOf<MemberInjectSite>()
  for ((index, signature) in scope.getCallableSignatures().withIndex()) {
    checkCanceledEvery(index)
    val view = callableBindingView(signature) ?: continue
    val symbol = view.symbol
    val ownerId = symbol.callableId?.classId ?: continue
    if (ownerId !in ownerIds) continue
    when (symbol) {
      is KaPropertySymbol -> {
        val injectIds = options.allInjectAnnotations
        val injected =
          symbol.hasAnyAnnotation(injectIds) ||
            symbol.backingFieldSymbol?.hasAnyAnnotation(injectIds) == true ||
            symbol.setter?.hasAnyAnnotation(injectIds) == true
        if (injected) {
          onDependencyType?.invoke(view.returnType)
          result +=
            MemberInjectSite(
              ownerId,
              symbol.psi as? KtElement,
              dependencyKey(view.returnType, symbol, options),
            )
        }
      }
      is KaNamedFunctionSymbol -> {
        if (symbol.hasAnyAnnotation(options.allInjectAnnotations)) {
          view.valueParameters.withIndex().mapTo(result) { (index, parameter) ->
            checkCanceledEvery(index)
            onDependencyType?.invoke(parameter.returnType)
            MemberInjectSite(
              ownerId,
              parameter.symbol.psi as? KtElement ?: symbol.psi as? KtElement,
              dependencyKey(parameter.returnType, parameter.symbol, options),
            )
          }
        }
      }
      else -> {}
    }
  }
  return result
}

internal fun KaSession.memberInjectSites(
  classSymbol: KaNamedClassSymbol,
  options: MetroOptions,
  onDeclarationFile: ((PsiFile) -> Unit)? = null,
): List<MemberInjectSite> {
  val result = mutableListOf<MemberInjectSite>()
  for ((index, owner) in memberInjectOwners(classSymbol, onDeclarationFile).withIndex()) {
    checkCanceledEvery(index)
    collectDeclaredMemberInjectKeys(owner, options, result)
  }
  return result
}

/** Classes whose declared injected members are included when [classSymbol] is injected. */
internal fun KaSession.memberInjectOwnerClassIds(classSymbol: KaNamedClassSymbol): Set<ClassId> {
  return memberInjectOwners(classSymbol).mapNotNullTo(linkedSetOf()) { it.classId }
}

/**
 * Reports files whose members or inheritance marker are read. The first unmarked superclass is a
 * dependency too, because adding its marker changes the inherited injection sites.
 */
internal fun KaSession.memberInjectOwners(
  classSymbol: KaNamedClassSymbol,
  onDeclarationFile: ((PsiFile) -> Unit)? = null,
): List<KaNamedClassSymbol> {
  val result = mutableListOf<KaNamedClassSymbol>()
  var current: KaNamedClassSymbol? = classSymbol
  var depth = 0
  if (onDeclarationFile != null) {
    classSymbol.psi?.containingFile?.let(onDeclarationFile)
  }
  while (current != null) {
    checkCanceledEvery(depth++)
    result += current
    val superclass = superClassSymbol(current) ?: break
    if (onDeclarationFile != null) {
      superclass.psi?.containingFile?.let(onDeclarationFile)
    }
    if (!superclass.hasAnyAnnotation(setOf(MetroClassIds.hasMemberInjections))) break
    current = superclass
  }
  return result
}

private fun KaSession.collectDeclaredMemberInjectKeys(
  classSymbol: KaNamedClassSymbol,
  options: MetroOptions,
  result: MutableList<MemberInjectSite>,
) {
  val injectIds = options.allInjectAnnotations
  for ((index, callable) in classSymbol.declaredMemberScope.callables.withIndex()) {
    checkCanceledEvery(index)
    when (callable) {
      is KaPropertySymbol -> {
        // @Inject has no PROPERTY target. A bare annotation lands on the backing field.
        val injected =
          callable.hasAnyAnnotation(injectIds) ||
            callable.backingFieldSymbol?.hasAnyAnnotation(injectIds) == true ||
            callable.setter?.hasAnyAnnotation(injectIds) == true
        if (injected) {
          result +=
            MemberInjectSite(
              classSymbol.classId,
              callable.psi as? KtElement,
              dependencyKey(callable, options),
            )
        }
      }
      is KaNamedFunctionSymbol ->
        if (callable.hasAnyAnnotation(injectIds)) {
          callable.valueParameters.withIndex().mapTo(result) { (index, parameter) ->
            checkCanceledEvery(index)
            MemberInjectSite(
              classSymbol.classId,
              parameter.psi as? KtElement ?: callable.psi as? KtElement,
              dependencyKey(parameter, options),
            )
          }
        }
      else -> {}
    }
  }
}

private fun KaSession.superClassSymbol(classSymbol: KaNamedClassSymbol): KaNamedClassSymbol? {
  for ((index, superType) in classSymbol.superTypes.withIndex()) {
    checkCanceledEvery(index)
    val symbol =
      (superType.fullyExpandedType as? KaClassType)?.symbol as? KaNamedClassSymbol ?: continue
    if (symbol.classKind == KaClassKind.CLASS) return symbol
  }
  return null
}

/** Class-literal list argument values, such as `excludes`, `replaces`, and `bindingContainers`. */
internal fun classListArgument(annotation: KaAnnotation, name: String): List<ClassId> {
  val argument =
    annotation.arguments.firstOrNull { it.name.asString() == name } ?: return emptyList()
  return when (val value = argument.expression) {
    is KaAnnotationValue.ArrayValue ->
      value.values.mapIndexedNotNull { index, annotationValue ->
        checkCanceledEvery(index)
        classLiteralClassId(annotationValue)
      }
    else -> listOfNotNull(classLiteralClassId(value))
  }
}
