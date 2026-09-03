// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTypeAlias

/**
 * Captures shared declarations and imports that can affect them using source syntax only. Package,
 * owner names, and declaration text retain changes to values, aliases, and containing objects.
 */
internal fun sharedDeclarationFingerprint(file: KtFile): String {
  val referencedNames = HashSet<String>()
  val declarations = buildString {
    appendSharedDeclarations(file.declarations, owner = "", referencedNames)
  }
  val imports = sortedSetOf<String>()
  for (directive in file.importDirectives) {
    ProgressManager.checkCanceled()
    val path = directive.importPath
    if (!directive.isValidImport || path == null) {
      // Incomplete imports can change resolution as the user types.
      imports += directive.text
      continue
    }
    val visibleName = directive.aliasName ?: path.fqName.shortName().asString()
    if (directive.isAllUnder || visibleName in referencedNames) {
      imports += path.toString()
    }
  }
  return buildString {
    append(file.packageFqName.asString())
    for (importPath in imports) {
      append('\n')
      append(importPath)
    }
    append(declarations)
  }
}

/** Returns whether this container owns shared declarations, including nested containers. */
private fun StringBuilder.appendSharedDeclarations(
  declarations: List<KtDeclaration>,
  owner: String,
  referencedNames: MutableSet<String>,
): Boolean {
  var hasSharedDeclarations = false
  for (declaration in declarations) {
    ProgressManager.checkCanceled()
    val isSharedDeclaration =
      declaration is KtTypeAlias ||
        (declaration is KtProperty && declaration.hasModifier(KtTokens.CONST_KEYWORD))
    when {
      isSharedDeclaration -> {
        hasSharedDeclarations = true
        append('\n')
        append(owner)
        append(declaration.text)
        collectReferencedNames(declaration, referencedNames)
      }

      declaration is KtClassOrObject -> {
        val hasNestedDeclarations =
          appendSharedDeclarations(
            declaration.declarations,
            "$owner${declaration.name}.",
            referencedNames,
          )
        if (hasNestedDeclarations) {
          hasSharedDeclarations = true
          // Imported supertypes can affect names resolved inside a nested constant or alias.
          for (header in declaration.children) {
            if (header !is KtClassBody) collectReferencedNames(header, referencedNames)
          }
        }
      }
    }
  }
  return hasSharedDeclarations
}

private fun collectReferencedNames(element: PsiElement, names: MutableSet<String>) {
  PsiTreeUtil.processElements(element) { child ->
    ProgressManager.checkCanceled()
    if (child is KtSimpleNameExpression) names += child.getReferencedName()
    true
  }
}
