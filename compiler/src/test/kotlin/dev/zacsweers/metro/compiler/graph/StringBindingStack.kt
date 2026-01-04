// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.MutableObjectList
import dev.zacsweers.metro.compiler.removeFirstOrNull
import org.jetbrains.kotlin.name.FqName

internal class StringBindingStack(override val graph: String) :
  BaseBindingStack<String, String, StringTypeKey, StringBindingStack.Entry, StringBindingStack> {
  override val entries = MutableObjectList<Entry>()
  override val graphFqName: FqName = FqName(graph)

  override fun push(entry: Entry) {
    entries.add(0, entry)
  }

  override fun pop() {
    entries.removeFirstOrNull() ?: error("Binding stack is empty!")
  }

  override fun copy(): StringBindingStack {
    return StringBindingStack(graph).also { it.entries.addAll(entries) }
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
