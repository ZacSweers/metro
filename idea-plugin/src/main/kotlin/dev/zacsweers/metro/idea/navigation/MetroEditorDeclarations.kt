// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Nearest declarations first; callers consume this traversal inside their background read action.
 */
internal fun metroEditorDeclarations(file: KtFile, offset: Int): Sequence<KtNamedDeclaration> =
  sequence {
    if (file.textLength == 0) return@sequence
    val element = file.findElementAt(offset.coerceIn(0, file.textLength - 1)) ?: return@sequence
    var declaration = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)
    while (declaration != null) {
      ProgressManager.checkCanceled()
      yield(declaration)
      declaration = PsiTreeUtil.getParentOfType(declaration, KtNamedDeclaration::class.java, true)
    }
  }
