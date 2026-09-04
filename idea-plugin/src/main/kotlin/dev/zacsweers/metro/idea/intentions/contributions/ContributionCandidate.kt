// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions.contributions

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.BoundTypeResolution
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.checkCanceledEvery
import dev.zacsweers.metro.idea.findAllMetaAnnotated
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.index.bindingContributionAnnotations
import dev.zacsweers.metro.idea.index.findInjectConstructorSymbol
import dev.zacsweers.metro.idea.index.implicitContributedBoundType
import dev.zacsweers.metro.idea.index.isInjectableKind
import dev.zacsweers.metro.idea.index.mapKeyInfo
import dev.zacsweers.metro.idea.index.renderKeyType
import dev.zacsweers.metro.idea.index.renderShortKeyType
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.qualifierAnnotation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.createUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeArgumentWithVariance
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.renderer.render

/** Detached choices for a source edit. Actions recheck eligibility before applying a choice. */
internal data class ContributionCandidate(
  val boundTypes: List<ContributionBoundType>,
  val existingScope: String?,
  val existingMapKey: Boolean,
)

/** Fully qualified source syntax preserves generic arguments and type-use annotations. */
internal data class ContributionBoundType(
  val renderedType: String,
  val label: String,
  /** The annotation can omit its `binding` argument without losing type annotations. */
  val implicit: Boolean,
  val hasMapKey: Boolean = false,
  /** The compiler's sole default choice, which the picker can select automatically. */
  val isDefault: Boolean = implicit,
)

/**
 * Resolves eligibility during a background read action and releases every Analysis API value before
 * returning. Existing binding contributions are left to their authored configuration.
 */
internal fun contributionCandidate(ktClass: KtClassOrObject): ContributionCandidate? {
  if (!ktClass.isValid || ktClass.isLocal) return null
  if (ktClass.name == null) return null
  if (ktClass.typeParameters.isNotEmpty()) return null
  if (ktClass.hasModifier(KtTokens.INNER_KEYWORD)) return null
  if (ktClass.hasModifier(KtTokens.EXPECT_KEYWORD)) return null
  if (!ktClass.hasAccessibleContainers()) return null
  val hasPotentialInjection =
    ktClass.annotationEntries.isNotEmpty() ||
      ktClass.primaryConstructor?.annotationEntries.orEmpty().isNotEmpty() ||
      ktClass.secondaryConstructors.any { it.annotationEntries.isNotEmpty() }
  if (ktClass !is KtObjectDeclaration && !hasPotentialInjection) return null
  val file = ktClass.containingFile as? KtFile ?: return null
  if (file.isCompiled) return null
  val virtualFile = file.virtualFile ?: return null
  val project = ktClass.project
  if (DumbService.isDumb(project)) return null
  if (!ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) return null
  val state = ktClass.metroIdeState()
  if (!state.isEnabled) return null

  return analyze(ktClass) {
    val symbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze null
    if (symbol.classId == null) return@analyze null
    val options = state.options
    if (symbol.hasAnyAnnotation(bindingContributionAnnotations(options))) return@analyze null
    if (!isConstructibleContribution(ktClass, symbol, options)) return@analyze null
    if (findAllMetaAnnotated(symbol, options.qualifierAnnotations).size > 1) return@analyze null
    if (findAllMetaAnnotated(symbol, options.scopeAnnotations).size > 1) return@analyze null
    if (findAllMetaAnnotated(symbol, options.mapKeyAnnotations).size > 1) return@analyze null
    val boundTypes = contributionBoundTypes(symbol, options)
    if (boundTypes.isEmpty()) return@analyze null
    ContributionCandidate(
      boundTypes,
      existingAggregationScope(ktClass, symbol, options),
      existingMapKey = mapKeyInfo(symbol, options, symbol.classId) != null,
    )
  }
}

/** Generated contribution declarations need access to the class and every containing class. */
private fun KtClassOrObject.hasAccessibleContainers(): Boolean {
  var current: PsiElement? = this
  while (current != null) {
    if (current is KtClassOrObject && !current.isContributionAccessible()) return false
    current = current.parent
  }
  return true
}

private fun KtModifierListOwner.isContributionAccessible(): Boolean {
  return !hasModifier(KtTokens.PRIVATE_KEYWORD) && !hasModifier(KtTokens.PROTECTED_KEYWORD)
}

/** Selects the same constructor as indexing and rejects conflicting or assisted injection. */
private fun KaSession.isConstructibleContribution(
  ktClass: KtClassOrObject,
  symbol: KaNamedClassSymbol,
  options: MetroOptions,
): Boolean {
  if (symbol.hasAnyAnnotation(options.assistedInjectAnnotations)) return false
  // Assisted factories require validation of their SAM and assisted target constructor before
  // a contribution can be authored.
  if (symbol.hasAnyAnnotation(options.assistedFactoryAnnotations)) return false
  if (ktClass is KtObjectDeclaration) return !symbol.hasAnyAnnotation(options.allInjectAnnotations)
  if (!symbol.isInjectableKind()) return false
  val constructors = symbol.memberScope.constructors.toList()
  if (constructors.any { it.hasAnyAnnotation(options.assistedInjectAnnotations) }) return false
  val injectedConstructors = constructors.filter {
    it.hasAnyAnnotation(options.allInjectAnnotations)
  }
  if (injectedConstructors.size > 1) return false
  val hasClassInject = symbol.hasAnyAnnotation(options.allInjectAnnotations)
  if (hasClassInject && injectedConstructors.isNotEmpty()) return false
  val constructor = findInjectConstructorSymbol(symbol, options) ?: return false
  val declaration = constructor.psi as? KtConstructor<*>
  if (declaration != null && !declaration.isContributionAccessible()) return false
  return constructor.valueParameters.none { it.hasAnyAnnotation(options.assistedAnnotations) }
}

/** Includes the default-bound type before declared supertypes, with one implicit choice at most. */
private fun KaSession.contributionBoundTypes(
  symbol: KaNamedClassSymbol,
  options: MetroOptions,
): List<ContributionBoundType> {
  val superTypes = symbol.superTypes.filter { !it.isAnyType }
  if (superTypes.any { !isClosedBoundType(it) }) return emptyList()
  val resolution = implicitContributedBoundType(symbol)
  val implicitType = (resolution as? BoundTypeResolution.Resolved)?.type
  val implicitRender = implicitType?.let { renderKeyType(it) }
  val classQualifier = qualifierAnnotation(symbol, options)
  val defaultTypes =
    when (resolution) {
      is BoundTypeResolution.Resolved -> listOf(resolution.type)
      is BoundTypeResolution.AmbiguousDefaultBinding -> resolution.types
      else -> emptyList()
    }
  val choices = defaultTypes + superTypes
  return choices
    .mapIndexedNotNull { index, type ->
      checkCanceledEvery(index)
      if (!isClosedBoundType(type)) return@mapIndexedNotNull null
      val rendered = renderKeyType(type)
      val label = renderShortKeyType(type)
      val typeQualifier = qualifierAnnotation(type, options)
      val inheritedQualifier = classQualifier.takeIf { typeQualifier == null }
      val mapKey = mapKeyInfo(type, options, symbol.classId)
      ContributionBoundType(
        // Metro inherits the class qualifier when the bound type has none. Keeping it on the
        // class also supports qualifier annotations without a TYPE target.
        renderedType = rendered,
        label = inheritedQualifier?.let { "${it.render(short = true)} $label" } ?: label,
        implicit = rendered == implicitRender && typeQualifier == null && mapKey == null,
        hasMapKey = mapKey != null,
        isDefault = rendered == implicitRender,
      )
    }
    .distinctBy { it.renderedType }
}

/** Free type parameters and unresolved nested arguments cannot form an annotation's bound type. */
private fun KaSession.isClosedBoundType(type: KaType): Boolean {
  val classType = type.fullyExpandedType as? KaClassType ?: return false
  if (classType.isMarkedNullable) return false
  if (classType.classId == StandardClassIds.Any || classType.classId == StandardClassIds.Nothing) {
    return false
  }
  return isResolvedClassType(type)
}

private fun KaSession.isResolvedClassType(type: KaType): Boolean {
  val expanded = type.fullyExpandedType
  if (expanded is KaErrorType) return false
  val classType = expanded as? KaClassType ?: return false
  return classType.typeArguments.withIndex().all { (index, argument) ->
    checkCanceledEvery(index)
    argument !is KaTypeArgumentWithVariance || isResolvedClassType(argument.type)
  }
}

/** Only Metro's native SingleIn argument supplies an unambiguous aggregation scope suggestion. */
private fun KaSession.existingAggregationScope(
  owner: KtClassOrObject,
  symbol: KaNamedClassSymbol,
  options: MetroOptions,
): String? {
  val annotation =
    symbol.annotations.singleOrNull { it.classId == MetroClassIds.singleIn } ?: return null
  val scopeId = annotationScopeKeys(annotation).singleOrNull() ?: return null
  val scope = findClass(scopeId) as? KaNamedClassSymbol ?: return null
  if (scope.hasAnyAnnotation(options.scopeAnnotations)) return null
  if (scope.hasAnyAnnotation(options.dependencyGraphAnnotations)) return null
  if (scope.hasAnyAnnotation(options.graphExtensionAnnotations)) return null
  val visibility = createUseSiteVisibilityChecker(owner.containingKtFile.symbol, null, owner)
  if (!visibility.isVisible(scope)) return null
  return scopeId.asSingleFqName().pathSegments().joinToString(".") { it.render() }
}
