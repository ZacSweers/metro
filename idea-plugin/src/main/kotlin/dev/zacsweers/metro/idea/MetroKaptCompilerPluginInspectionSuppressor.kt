// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement

/** Suppresses IntelliJ's file-level kapt configuration warning in Metro-enabled modules. */
class MetroKaptCompilerPluginInspectionSuppressor : InspectionSuppressor {

  override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
    if (toolId != KAPT_COMPILER_PLUGIN_INSPECTION_ID) return false
    if (!MetroSettings.getInstance(element.project).state.suppressKaptConfigurationWarning) {
      return false
    }

    return element.metroIdeState().isEnabled
  }

  override fun getSuppressActions(
    element: PsiElement?,
    toolId: String,
  ): Array<SuppressQuickFix> {
    return SuppressQuickFix.EMPTY_ARRAY
  }
}

// Registered by the Kotlin plugin since IntelliJ 2026.2. Match the explicit short name instead of
// depending on the Kotlin plugin's internal inspection class.
private const val KAPT_COMPILER_PLUGIN_INSPECTION_ID = "KaptKotlinCompilerPlugin"
