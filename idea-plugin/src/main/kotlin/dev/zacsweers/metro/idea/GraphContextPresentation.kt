// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.module.ModuleUtilCore
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath

internal fun GraphContext.presentableName(includeFile: Boolean = false): String {
  return buildString {
    append(graph.name ?: "<unknown>")
    val parents = chain.drop(1)
    if (parents.isNotEmpty()) {
      append(" via ")
      append(parents.joinToString(" > ") { it.name ?: "<unknown>" })
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
