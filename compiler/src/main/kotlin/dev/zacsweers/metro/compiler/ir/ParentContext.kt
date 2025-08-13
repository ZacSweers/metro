package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrClass

internal class ParentContext {
  private val keys = mutableSetOf<IrTypeKey>()
  private val usedKeys = mutableSetOf<IrTypeKey>()
  private val parentGraphStack = ArrayDeque<DependencyGraphNode>()
  private val parentScopes = mutableSetOf<IrAnnotation>()

  fun add(key: IrTypeKey) {
    keys += key
  }

  fun addAll(keys: Collection<IrTypeKey>) {
    this.keys += keys
  }

  fun mark(key: IrTypeKey) {
    // Don't check if it's an "available" key, because a child graph may discover a
    // constructor-injected class with a matching scope and mark it anyway
    usedKeys += key
  }

  fun pushParentGraph(node: DependencyGraphNode) {
    parentGraphStack.addLast(node)
    parentScopes.addAll(node.scopes)
  }

  fun popParentGraph() {
    val removed = parentGraphStack.removeLast()
    parentScopes.removeAll(removed.scopes)
  }

  val currentParentGraph: IrClass
    get() =
      parentGraphStack.lastOrNull()?.metroGraphOrFail
        ?: error(
          "No parent graph on stack - this should only be accessed when processing extensions"
        )

  fun containsScope(scope: IrAnnotation): Boolean = scope in parentScopes

  operator fun contains(key: IrTypeKey): Boolean = key in keys

  fun availableKeys(): Set<IrTypeKey> = keys

  fun usedKeys(): Set<IrTypeKey> = usedKeys
}
