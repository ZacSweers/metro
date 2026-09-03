// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions.injection

import com.intellij.openapi.roots.ProjectFileIndex
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.index.hasClassLevelInject
import dev.zacsweers.metro.idea.index.isInjectableKind
import dev.zacsweers.metro.idea.metroIdeState
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/** Keeps intention checks and delayed constructor choices on the same injection rules. */
internal fun injectionAnnotationTargets(klass: KtClass): List<KtModifierListOwner> {
  if (!klass.isValid || !isInjectionSourceFile(klass.containingKtFile)) return emptyList()
  val state = klass.metroIdeState()
  if (!state.isEnabled) return emptyList()
  if (klass.isLocal || klass.hasModifier(KtTokens.INNER_KEYWORD)) return emptyList()
  if (klass.hasModifier(KtTokens.EXPECT_KEYWORD)) return emptyList()
  var enclosing: KtClassOrObject? = klass
  while (enclosing != null) {
    if (!enclosing.isVisibleForInjection()) return emptyList()
    enclosing = enclosing.containingClassOrObject
  }

  val options = state.options
  return analyze(klass) {
    val symbol = klass.symbol as? KaNamedClassSymbol ?: return@analyze emptyList()
    if (!symbol.isInjectableKind()) return@analyze emptyList()
    if (hasClassLevelInject(symbol, options)) return@analyze emptyList()
    if (symbol.hasAnyAnnotation(options.assistedFactoryAnnotations)) return@analyze emptyList()

    val primary = klass.primaryConstructor
    val constructors = listOfNotNull(primary) + klass.secondaryConstructors
    for (constructor in constructors) {
      val constructorSymbol = constructor.symbol
      if (constructorSymbol.hasAnyAnnotation(options.allInjectAnnotations)) {
        return@analyze emptyList()
      }
      if (
        constructorSymbol.valueParameters.any { it.hasAnyAnnotation(options.assistedAnnotations) }
      ) {
        return@analyze emptyList()
      }
    }

    // A class without any declared constructors has an implicit primary constructor.
    if (constructors.isEmpty()) return@analyze listOf(klass)
    // Keep the selection explicit when other constructors exist, even if they are hidden.
    if (primary != null && constructors.size == 1) {
      return@analyze if (primary.isVisibleForInjection()) listOf(klass) else emptyList()
    }
    constructors.filter { it.isVisibleForInjection() }
  }
}

/** The platform requests write access when applying the command. Library PSI stays ineligible. */
internal fun isInjectionSourceFile(file: KtFile): Boolean {
  if (!file.isValid || file.isCompiled) return false
  val virtualFile = file.virtualFile ?: return false
  return ProjectFileIndex.getInstance(file.project).isInSourceContent(virtualFile)
}

private fun KtModifierListOwner.isVisibleForInjection(): Boolean {
  return !hasModifier(KtTokens.PRIVATE_KEYWORD) && !hasModifier(KtTokens.PROTECTED_KEYWORD)
}
