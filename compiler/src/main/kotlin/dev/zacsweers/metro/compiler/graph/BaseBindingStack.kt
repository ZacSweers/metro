package dev.zacsweers.metro.compiler.graph

import org.jetbrains.kotlin.name.FqName

internal interface BaseBindingStack<
  ClassType : Any,
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  Entry : BaseBindingStack.BaseEntry<Type, TypeKey, *>,
> {
  val graph: ClassType
  val entries: List<Entry>
  val graphFqName: FqName

  fun push(entry: Entry)

  fun pop()

  fun entryFor(key: TypeKey): Entry?

  fun entriesSince(key: TypeKey): List<Entry>

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
          append("[${graph.asString()}]")
          append(' ')
          append(it)
        }
      }
    }
  }
}
