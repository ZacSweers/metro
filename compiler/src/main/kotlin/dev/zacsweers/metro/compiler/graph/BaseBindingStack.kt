// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.ObjectList
import org.jetbrains.kotlin.name.FqName

internal interface BaseBindingStack<
  ClassType : Any,
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  Entry : BaseBindingStack.BaseEntry<Type, TypeKey, *>,
  Impl : BaseBindingStack<ClassType, Type, TypeKey, Entry, Impl>,
> {
  val graph: ClassType
  val entries: ObjectList<Entry>
  val graphFqName: FqName

  fun push(entry: Entry)

  fun pop()

  fun copy(): Impl

  fun entryFor(key: TypeKey): Entry?

  fun entriesSince(key: TypeKey): List<Entry> {
    // Top entry is always the key currently being processed, so exclude it from analysis with
    // dropLast(1)
    val inFocus = entries.asList().asReversed().dropLast(1)
    if (inFocus.isEmpty()) return emptyList()

    val first = inFocus.indexOfFirst { !it.isSynthetic && it.typeKey == key }
    if (first == -1) return emptyList()

    // path from the earlier duplicate up to the key just below the current one
    return inFocus.subList(first, inFocus.size)
  }

  interface BaseEntry<
    Type : Any,
    TypeKey : BaseTypeKey<Type, *, *>,
    ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, *>,
  > {
    val contextKey: ContextualTypeKey
    val usage: String?
    val graphContext: String?
    val displayTypeKey: TypeKey
    /**
     * Indicates this entry is informational only and not an actual functional binding that should
     * participate in validation.
     */
    val isSynthetic: Boolean
    val typeKey: TypeKey
      get() = contextKey.typeKey

    fun render(graph: FqName, short: Boolean): String {
      return buildString {
        append(displayTypeKey.render(short))
        usage?.let {
          append(' ')
          append(it)
        }
        graphContext?.let {
          appendLine()
          append("    ")
          append('[')
          append(graph.asString())
          append(']')
          append(' ')
          append(it)
        }
      }
    }
  }
}
