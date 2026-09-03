// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectFileIndex
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.KaGraphDeclaration

/** Explicit choosers can qualify each declaration to distinguish equal short graph names. */
internal fun GraphContext.presentableName(
  includeFile: Boolean = false,
  qualifiedNames: Boolean = false,
): String {
  fun declarationName(graph: KaGraphDeclaration): String {
    if (qualifiedNames)
      graph.classId?.asSingleFqName()?.asString()?.let {
        return it
      }
    return graph.name ?: "<unknown>"
  }
  return buildString {
    append(declarationName(graph))
    val parents = chain.drop(1)
    if (parents.isNotEmpty()) {
      append(" via ")
      append(parents.joinToString(" > ", transform = ::declarationName))
    }
    val dynamic = dynamicGraph
    when {
      dynamic != null -> {
        append(" (dynamic at ")
        append(dynamic.pointer.virtualFile?.name ?: "<unknown>")
        append(": ")
        append(dynamic.containerKeys.map { it.type.shortType }.sorted().joinToString())
        append(')')
      }
      includeFile -> {
        graph.pointer.virtualFile?.name?.let { fileName ->
          append(" (")
          graph.pointer.element?.let(ModuleUtilCore::findModuleForPsiElement)?.name?.let {
            append(it)
            append(": ")
          }
          append(fileName)
          append(')')
        }
      }
    }
  }
}

/** Identifies the graph's root compilation, including a dynamic graph's caller module. */
internal fun GraphContext.compilationContextName(): String {
  val compilationPointer = dynamicGraph?.pointer ?: rootGraph.pointer
  val rootFile = compilationPointer.virtualFile
  val module = rootFile?.let {
    ProjectFileIndex.getInstance(compilationPointer.project).getModuleForFile(it)
  }
  return buildString {
    append(presentableName(qualifiedNames = true))
    if (rootFile != null) {
      append(" (")
      module?.name?.let { append(it).append(": ") }
      append(rootFile.name)
      append(')')
    }
  }
}

internal fun GraphPath.presentableName(): String {
  return buildString {
    append(segments.firstOrNull()?.classId?.shortClassName?.asString() ?: "<unknown>")
    val parents = segments.drop(1)
    if (parents.isNotEmpty()) {
      append(" via ")
      append(
        parents.joinToString(" > ") {
          it.classId?.shortClassName?.asString() ?: "<unknown>"
        }
      )
    }
    dynamicGraphId?.let {
      append(" (dynamic in ")
      append(it.callerFile.name)
      append(": ")
      append(it.containerKeys.map { key -> key.type.shortType }.sorted().joinToString())
      append(')')
    }
  }
}
