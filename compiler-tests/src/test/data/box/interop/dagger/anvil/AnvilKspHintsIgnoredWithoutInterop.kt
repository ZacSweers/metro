// MODULE: lib
// ENABLE_ANVIL_KSP
// ANVIL_GENERATE_DAGGER_FACTORIES_ONLY: false
// DISABLE_METRO
// FILE: Contributions.kt
package test

import com.squareup.anvil.annotations.ContributesTo

abstract class LibScope

@ContributesTo(LibScope::class)
interface MessageAccessor {
  val message: String
}

// MODULE: main(lib)
// WITH_ANVIL
// ENABLE_ANVIL_INTEROP: false
// ENABLE_DAGGER_INTEROP

import test.LibScope
import test.MessageAccessor

@DependencyGraph(LibScope::class)
interface AppGraph

fun box(): String {
  val graph: Any = createGraph<AppGraph>()
  assertFalse(graph is MessageAccessor)
  return "OK"
}
