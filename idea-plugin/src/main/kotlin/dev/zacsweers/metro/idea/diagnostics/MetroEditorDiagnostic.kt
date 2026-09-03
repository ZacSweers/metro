// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.diagnostics

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.idea.compilationContextName
import dev.zacsweers.metro.idea.graph.CachedValidation
import dev.zacsweers.metro.idea.graph.KaGraphDiagnostic
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** A current validation diagnostic anchored to a declaration in the inspected source file. */
internal class MetroEditorDiagnostic(
  val anchor: PsiElement,
  val result: KaGraphValidationResult.Completed,
  val diagnostic: KaGraphDiagnostic,
  contextName: String,
) {
  val description: String = "${diagnostic.diagnostic.title} ($contextName)"
}

/** Converts retained results under read access. It performs no binding lookup or validation. */
internal fun metroDiagnosticsForFile(
  file: KtFile,
  cachedResults: List<CachedValidation>,
  severity: MetroSeverity,
): List<MetroEditorDiagnostic> {
  val diagnostics = mutableListOf<MetroEditorDiagnostic>()
  val fileIndex = ProjectFileIndex.getInstance(file.project)
  val sourceFiles = HashMap<VirtualFile, Boolean>()
  fun isProjectSource(pointer: SmartPsiElementPointer<out PsiElement>?): Boolean {
    val virtualFile = pointer?.virtualFile ?: return false
    return sourceFiles.getOrPut(virtualFile) { fileIndex.isInSourceContent(virtualFile) }
  }
  for (cached in cachedResults) {
    ProgressManager.checkCanceled()
    if (cached.stale) continue
    val result = cached.result as? KaGraphValidationResult.Completed ?: continue
    val sourceGraph = result.context.chain.firstOrNull { isProjectSource(it.pointer) }?.pointer
    val caller = result.context.dynamicGraph?.pointer?.takeIf(::isProjectSource)
    val fallback = caller ?: sourceGraph
    var contextName: String? = null
    for (diagnostic in result.diagnostics) {
      ProgressManager.checkCanceled()
      if (diagnostic.severity != severity) continue
      val related = diagnostic.related.map { it.pointer }.filter(::isProjectSource).distinct()
      val pointers =
        if (related.isNotEmpty()) {
          related
        } else {
          val request = diagnostic.stack.firstOrNull { isProjectSource(it.pointer) }?.pointer
          // Binary child diagnostics remain visible at the source compilation that creates them.
          listOfNotNull(request ?: fallback)
        }
      for (pointer in pointers) {
        // Each file's inspection resolves only its own diagnostic anchors.
        if (pointer.virtualFile != file.virtualFile) continue
        val anchor = sourceAnchor(pointer.element) ?: continue
        val name = contextName ?: result.context.compilationContextName().also { contextName = it }
        diagnostics += MetroEditorDiagnostic(anchor, result, diagnostic, name)
      }
    }
  }
  return diagnostics
}

/** Names give concise highlights; type references and other explicit sites retain their range. */
private fun sourceAnchor(element: PsiElement?): PsiElement? {
  if (element == null || !element.isValid) return null
  val file = element.containingFile as? KtFile ?: return null
  if (file.isCompiled) return null
  return (element as? KtNamedDeclaration)?.nameIdentifier ?: element
}
