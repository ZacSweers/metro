// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.explanation

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.graph.explanation.BindingDeclaration
import dev.zacsweers.metro.compiler.graph.explanation.BindingSourceLocation
import dev.zacsweers.metro.compiler.graph.explanation.bindingExplanationId
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.KaBinding
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Captures source identities in the query's read action; snapshots contain no PSI or absolute
 * paths.
 */
internal class BindingExplanationSources(private val project: Project) {
  private val fileIndex = ProjectFileIndex.getInstance(project)

  fun declaration(pointer: SmartPsiElementPointer<*>, label: String): BindingDeclaration {
    val element = pointer.element
    val path = pointer.virtualFile?.let(::filePath)
    val names =
      generateSequence(element) { it.parent }
        .filterIsInstance<KtNamedDeclaration>()
        .mapNotNull { it.name }
        .toList()
        .asReversed()
        .joinToString(".")
    val id = bindingExplanationId(path.orEmpty(), names, element?.textOffset?.toString().orEmpty())
    return BindingDeclaration(id, label, path?.let { sourceLocation(element, it) })
  }

  fun candidateId(binding: KaBinding, declaration: BindingDeclaration): String =
    bindingExplanationId(
      declaration.id,
      binding.typeKey.render(short = false),
      binding.label,
      binding.ownerGraphId?.let(::graphId).orEmpty(),
      binding.includedContainerKey?.render(short = false).orEmpty(),
      binding.multibindingId.orEmpty(),
      binding.mapKeyValue.orEmpty(),
      binding.contributionScopes.map { it.asFqNameString() }.sorted().joinToString(),
    )

  fun graphId(id: GraphDeclarationId): String =
    bindingExplanationId(
      id.classId?.asFqNameString().orEmpty(),
      id.file?.let(::filePath).orEmpty(),
    )

  fun contextId(path: GraphPath): String {
    val segments = path.segments.map(::graphId)
    val dynamic = path.dynamicGraphId
    if (dynamic == null) return bindingExplanationId("graph", *segments.toTypedArray())
    return bindingExplanationId(
      "dynamic_graph",
      *segments.toTypedArray(),
      dynamic.requestedTypeClassId.asFqNameString(),
      filePath(dynamic.callerFile),
      bindingExplanationId(
        *dynamic.containerKeys.map { it.render(short = false) }.sorted().toTypedArray()
      ),
    )
  }

  /**
   * Content-root-relative names distinguish source modules; archive names identify binary owners.
   */
  private fun filePath(file: VirtualFile): String {
    val contentRoot = fileIndex.getContentRootForFile(file)
    val relative = contentRoot?.let { VfsUtilCore.getRelativePath(file, it, '/') }
    if (relative != null) {
      val moduleName = fileIndex.getModuleForFile(file)?.name
      return listOfNotNull(moduleName, relative).joinToString("/")
    }
    val basePath = project.basePath?.trimEnd('/')
    if (basePath != null && file.path.startsWith("$basePath/"))
      return file.path.removePrefix("$basePath/")
    val archiveSeparator = file.path.indexOf("!/")
    if (archiveSeparator >= 0) {
      val archive = file.path.substring(0, archiveSeparator).substringAfterLast('/')
      return archive + file.path.substring(archiveSeparator)
    }
    return file.name
  }

  private fun sourceLocation(element: PsiElement?, path: String): BindingSourceLocation {
    val document = element?.containingFile?.viewProvider?.document
    if (element == null || document == null) return BindingSourceLocation(path)
    val line = document.getLineNumber(element.textOffset)
    val column = element.textOffset - document.getLineStartOffset(line)
    return BindingSourceLocation(path, line + 1, column + 1)
  }
}
