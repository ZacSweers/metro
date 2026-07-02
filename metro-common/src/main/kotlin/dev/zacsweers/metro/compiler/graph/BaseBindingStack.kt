// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.diagnostics.Note
import org.jetbrains.kotlin.name.FqName

public interface BaseBindingStack<
  ClassType : Any,
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  Entry : BaseBindingStack.BaseEntry<Type, TypeKey, *>,
  Impl : BaseBindingStack<ClassType, Type, TypeKey, Entry, Impl>,
> {
  public val graph: ClassType
  public val entries: List<Entry>
  public val graphFqName: FqName

  public fun push(entry: Entry)

  public fun pop()

  public fun copy(): Impl

  public fun entryFor(key: TypeKey): Entry?

  public fun entriesSince(key: TypeKey): List<Entry> {
    // Top entry is always the key currently being processed, so exclude it from analysis with
    // dropLast(1)
    val inFocus = entries.asReversed().dropLast(1)
    if (inFocus.isEmpty()) return emptyList()

    val first = inFocus.indexOfFirst { !it.isSynthetic && it.typeKey == key }
    if (first == -1) return emptyList()

    // path from the earlier duplicate up to the key just below the current one
    return inFocus.subList(first, inFocus.size)
  }

  public interface BaseEntry<
    Type : Any,
    TypeKey : BaseTypeKey<Type, *, *>,
    ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, *>,
  > {
    public val contextKey: ContextualTypeKey
    public val usage: String?
    public val graphContext: String?
    public val displayTypeKey: TypeKey
    public val diagnosticNotes: List<Note>
      get() = emptyList()

    /**
     * Indicates this entry is informational only and not an actual functional binding that should
     * participate in validation.
     */
    public val isSynthetic: Boolean
    public val typeKey: TypeKey
      get() = contextKey.typeKey

    public fun render(graph: FqName, short: Boolean): String {
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

public inline fun <
  T,
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  Entry : BaseBindingStack.BaseEntry<Type, TypeKey, *>,
  Impl : BaseBindingStack<*, Type, TypeKey, Entry, Impl>,
> Impl.withEntry(entry: Entry?, block: () -> T): T {
  if (entry == null) return block()
  push(entry)
  val result = block()
  pop()
  return result
}
