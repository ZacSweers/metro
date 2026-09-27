// MODULE: lib
// ENABLE_ANVIL_KSP
// ANVIL_GENERATE_DAGGER_FACTORIES_ONLY: false
// DISABLE_METRO
// FILE: Bindings.kt
// Anvil's rank is read from the bound class like Metro's priority.
package test

import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject

abstract class LibScope

interface Greeter {
  fun greet(): String
}

@ContributesBinding(LibScope::class)
class RealGreeter @Inject constructor() : Greeter {
  override fun greet(): String = "real"
}

@ContributesBinding(LibScope::class, rank = 100)
class HighRankGreeter @Inject constructor() : Greeter {
  override fun greet(): String = "high"
}

interface Logger {
  fun name(): String
}

@ContributesBinding(LibScope::class)
class RealLogger @Inject constructor() : Logger {
  override fun name(): String = "real"
}

// Anvil binds contributed objects with @Provides instead of @Binds.
@ContributesBinding(LibScope::class, rank = 100)
object HighRankLogger : Logger {
  override fun name(): String = "high"
}

// MODULE: main(lib)
// WITH_ANVIL
// ENABLE_DAGGER_INTEROP

import test.Greeter
import test.LibScope
import test.Logger

@DependencyGraph(LibScope::class)
interface AppGraph {
  val greeter: Greeter
  val logger: Logger
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("high", graph.greeter.greet())
  assertEquals("high", graph.logger.name())
  return "OK"
}
