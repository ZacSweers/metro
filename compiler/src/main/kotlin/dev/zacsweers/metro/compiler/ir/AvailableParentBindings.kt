package dev.zacsweers.metro.compiler.ir

internal class AvailableParentBindings {
  private val keys = mutableSetOf<IrTypeKey>()
  private val usedKeys = mutableSetOf<IrTypeKey>()

  fun add(key: IrTypeKey) {
    keys += key
  }

  fun addAll(keys: Collection<IrTypeKey>) {
    this.keys += keys
  }

  fun mark(key: IrTypeKey) {
    check(key in keys) {
      "Tried to mark $key as used, but it wasn't available in the parent graph!"
    }
    usedKeys += key
  }

  operator fun contains(key: IrTypeKey): Boolean = key in keys

  fun availableKeys(): Set<IrTypeKey> = keys

  fun usedKeys(): Set<IrTypeKey> = usedKeys
}
