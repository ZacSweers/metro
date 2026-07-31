// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.unused

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement

/**
 * Suppresses Kotlin unused-declaration reports that do not honor implicit usage metadata.
 *
 * Keep this narrowly delegated to [MetroImplicitUsageProvider]'s rules: the provider is the primary
 * framework-usage hook, while this covers the Kotlin unused inspection path verified by fixture
 * tests.
 */
class MetroUnusedDeclarationInspectionSuppressor : InspectionSuppressor {

  override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
    return toolId.isUnusedDeclarationInspection() && element.isMetroImplicitUsage()
  }

  override fun getSuppressActions(
    element: PsiElement?,
    toolId: String,
  ): Array<SuppressQuickFix> {
    return SuppressQuickFix.EMPTY_ARRAY
  }
}

// The unused-declaration inspection short names across IntelliJ and the Kotlin K1/K2 plugins. A
// substring match would over-suppress unrelated inspections like unused imports and parameters.
private val UNUSED_DECLARATION_TOOL_IDS =
  setOf("unused", "UnusedSymbol", "UnusedDeclaration", "KotlinUnusedSymbol")

private fun String.isUnusedDeclarationInspection(): Boolean {
  return UNUSED_DECLARATION_TOOL_IDS.any { equals(it, ignoreCase = true) }
}
