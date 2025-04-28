package dev.zacsweers.metro.compiler.graph

import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.name.FqName

internal class StringBindingStack(override val graph: String) :
  BaseBindingStack<String, String, StringTypeKey, StringBindingStack.Entry> {
  override val entries = ArrayDeque<Entry>()
  override val graphFqName: FqName = FqName(graph)

  override fun push(entry: Entry) {
    entries.push(entry)
  }

  override fun pop() {
    entries.pop()
  }

  override fun entryFor(key: StringTypeKey): Entry? {
    return entries.firstOrNull { entry -> entry.typeKey == key }
  }

  override fun entriesSince(key: StringTypeKey): List<Entry> {
    val reversed = entries.asReversed()
    val index = reversed.indexOfFirst { !it.contextKey.isIntoMultibinding && it.typeKey == key }
    if (index == -1) return emptyList()
    return reversed.slice(index until reversed.size).filterNot { it.isSynthetic }
  }

  class Entry(
    override val contextKey: StringContextualTypeKey,
    override val usage: String? = null,
    override val graphContext: String? = null,
    override val displayTypeKey: StringTypeKey = contextKey.typeKey,
    override val isSynthetic: Boolean = false,
  ) : BaseBindingStack.BaseEntry<String, StringTypeKey, StringContextualTypeKey> {

  }
}
