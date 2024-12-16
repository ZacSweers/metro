package dev.zacsweers.lattice.internal

import dev.zacsweers.lattice.Provider

private object AbsentProvider : Provider<Any> {
  override fun invoke() = error("Never called")
}

@Suppress("UNCHECKED_CAST")
public fun <T : Any> absentProvider(): Provider<T> = AbsentProvider as Provider<T>