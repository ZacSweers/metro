// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.name.FqName

internal class StringBindingStack(override val graph: String) :
  BaseBindingStack<String, String, StringTypeKey, StringBindingStack.Entry> {
  override val entries = ArrayDeque<Entry>()
  override val graphFqName: FqName = FqName(graph)

  override fun push(entry: Entry) {
    entries.addFirst(entry)
  }

  override fun pop() {
    entries.removeFirstOrNull() ?: error("Binding stack is empty!")
  }

  override fun entryFor(key: StringTypeKey): Entry? {
    return entries.firstOrNull { entry -> entry.typeKey == key }
  }

  override fun toString(): String {
    return entries.joinToString(" -> ") { it.toString() }
  }

  class Entry(
    override val contextKey: StringContextualTypeKey,
    override val usage: String? = null,
    override val graphContext: String? = null,
    override val displayTypeKey: StringTypeKey = contextKey.typeKey,
    override val isSynthetic: Boolean = false,
  ) : BaseBindingStack.BaseEntry<String, StringTypeKey, StringContextualTypeKey> {
    override fun toString() = contextKey.toString()
  }
}
