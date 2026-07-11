// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.OptionalBindingBehavior
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.model.HintAvailability
import dev.zacsweers.metro.idea.model.KaBinding
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

internal fun shortNames(classIds: Set<ClassId>): Set<String> {
  return classIds.mapToSet { it.shortClassName.asString() }
}

/**
 * Builds a [KaBinding] from a [BindingData], anchoring it at [pointer]. Callers override only the
 * fields that differ from the computed data, such as contribution-derived origin and scopes.
 */
internal fun BindingData.toKaBinding(
  pointer: SmartPsiElementPointer<out PsiElement>,
  containerId: ClassId? = null,
  originClassId: ClassId? = this.originClassId,
  implementationName: String? = this.implementationName,
  replaces: Set<ClassId> = this.replaces,
  contributionScopes: Set<ClassId> = this.contributionScopes,
  hintAvailability: HintAvailability? = null,
): KaBinding {
  return when (kind) {
    BindingData.Kind.CONSTRUCTOR_INJECTED ->
      KaBinding.ConstructorInjected(
        pointer = pointer,
        typeKey = key,
        scope = scope,
        implementationName = implementationName,
        originClassId = originClassId,
        replaces = replaces,
        contributionScopes = contributionScopes,
        dependencies = dependencies,
        hintAvailability = hintAvailability,
      )
    BindingData.Kind.PROVIDED ->
      KaBinding.Provided(
        pointer = pointer,
        typeKey = key,
        scope = scope,
        implementationName = implementationName,
        multibindingId = multibindingId,
        mapKeyValue = mapKeyValue,
        originClassId = originClassId,
        containerId = containerId,
        replaces = replaces,
        contributionScopes = contributionScopes,
        dependencies = dependencies,
        hintAvailability = hintAvailability,
      )
    BindingData.Kind.ALIAS ->
      KaBinding.Alias(
        pointer = pointer,
        typeKey = key,
        consumedKey = consumedKey,
        scope = scope,
        implementationName = implementationName,
        multibindingId = multibindingId,
        mapKeyValue = mapKeyValue,
        originClassId = originClassId,
        containerId = containerId,
        replaces = replaces,
        contributionScopes = contributionScopes,
        isClassContribution = isClassContribution,
        hintAvailability = hintAvailability,
      )
    BindingData.Kind.MULTIBINDING ->
      KaBinding.Multibinding(
        pointer = pointer,
        typeKey = key,
        scope = scope,
        originClassId = originClassId,
        containerId = containerId,
        replaces = replaces,
        contributionScopes = contributionScopes,
        allowEmpty = allowEmpty,
        hintAvailability = hintAvailability,
      )
    BindingData.Kind.BOUND_INSTANCE ->
      KaBinding.BoundInstance(pointer = pointer, typeKey = key, containerId = containerId)
    BindingData.Kind.CUSTOM_WRAPPER ->
      KaBinding.CustomWrapper(
        pointer = pointer,
        typeKey = key,
        implementationName = implementationName,
        originClassId = originClassId,
        containerId = containerId,
        replaces = replaces,
        contributionScopes = contributionScopes,
        hintAvailability = hintAvailability,
      )
  }
}

/**
 * The graph an instance binding (a `@Provides` factory parameter) belongs to: the factory's
 * enclosing graph when the factory is nested, otherwise the graph type the factory's creator
 * function returns (top-level `createGraphFactory`-style factories).
 */
internal fun KaSession.instanceBindingContainerId(parameter: KtParameter): ClassId? {
  val createFunction = parameter.ownerFunction as? KtNamedFunction ?: return null
  val factory = createFunction.containingClassOrObject
  factory?.containingClassOrObject?.getClassId()?.let {
    return it
  }
  val returnType = (createFunction.symbol as? KaNamedFunctionSymbol)?.returnType?.fullyExpandedType
  (returnType as? KaClassType)?.classId?.let {
    return it
  }
  return factory?.getClassId()
}

/**
 * Scope class ids from [this@scopeKeysFor]'s annotations matching [annotationClassIds], or null
 * when none of the annotations are present.
 */
internal fun KaAnnotated.scopeKeys(annotationClassIds: Set<ClassId>): Set<ClassId>? {
  val annotations = annotations.filter { it.classId in annotationClassIds }
  if (annotations.isEmpty()) return null
  return annotations.flatMapToSet { annotationScopeKeys(it) }
}

internal fun KaSession.findInjectConstructor(
  ktClass: KtClassOrObject,
  classSymbol: KaNamedClassSymbol,
  options: MetroOptions,
): KtConstructor<*>? {
  // Non-injectable kinds have no graph-resolved constructor, so they originate no consumers.
  if (!classSymbol.isInjectableKind()) return null
  val injectish = options.allInjectAnnotations
  val classLevel =
    classSymbol.hasAnyAnnotation(injectish) ||
      (options.contributesAsInject &&
        classSymbol.annotations.any { it.classId in bindingContributionAnnotations(options) })
  val constructors = listOfNotNull(ktClass.primaryConstructor) + ktClass.secondaryConstructors
  val annotatedConstructor = constructors.firstOrNull { ctor ->
    ctor.symbol.hasAnyAnnotation(injectish)
  }
  return annotatedConstructor ?: if (classLevel) ktClass.primaryConstructor else null
}

/**
 * Whether a consumer permits absence: a native `@OptionalBinding`/`@OptionalDependency` marker, or
 * a defaulted parameter under `DEFAULT` behavior. Under `REQUIRE_OPTIONAL_BINDING` only the
 * annotation counts; `DISABLED` never treats a site as optional.
 */
internal fun KaCallableSymbol.isOptionalConsumer(options: MetroOptions): Boolean {
  val behavior = options.optionalBindingBehavior
  if (behavior == OptionalBindingBehavior.DISABLED) return false
  if (hasAnyAnnotation(options.optionalBindingAnnotations)) return true
  if (!behavior.requiresAnnotatedParameters) {
    return (this as? KaValueParameterSymbol)?.hasDefaultValue == true
  }
  return false
}

internal fun KtCallableDeclaration.isAnnotatedWithAny(classIds: Set<ClassId>): Boolean {
  // Cheap PSI pre-check by short name only; used to avoid registering @Binds params as consumers
  // twice (the analysis-verified path already handles them).
  val shortNames = classIds.mapToSet { it.shortClassName.asString() }
  return annotationEntries.any { it.shortName?.asString() in shortNames }
}
