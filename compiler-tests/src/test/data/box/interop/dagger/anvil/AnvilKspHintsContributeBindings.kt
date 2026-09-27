// MODULE: lib
// ENABLE_ANVIL_KSP
// ANVIL_GENERATE_DAGGER_FACTORIES_ONLY: false
// DISABLE_METRO
// FILE: Bindings.kt
// Anvil turns each contributed binding into a generated module with its own hint.
package test

import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesMultibinding
import dagger.multibindings.StringKey
import javax.inject.Inject

abstract class LibScope

interface Greeter {
  fun greet(): String
}

@ContributesBinding(LibScope::class)
class RealGreeter @Inject constructor() : Greeter {
  override fun greet(): String = "Hello"
}

interface Plugin {
  val name: String
}

@ContributesMultibinding(LibScope::class)
class SetPlugin @Inject constructor() : Plugin {
  override val name: String = "set"
}

@StringKey("map")
@ContributesMultibinding(LibScope::class)
class MapPlugin @Inject constructor() : Plugin {
  override val name: String = "map"
}

// MODULE: main(lib)
// WITH_ANVIL
// ENABLE_DAGGER_INTEROP

import test.Greeter
import test.LibScope
import test.Plugin

@DependencyGraph(LibScope::class)
interface AppGraph {
  val greeter: Greeter
  val plugins: Set<Plugin>
  val pluginsByName: Map<String, Plugin>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.greeter.greet())
  assertEquals(setOf("set"), graph.plugins.mapTo(mutableSetOf()) { it.name })
  assertEquals(setOf("map"), graph.pluginsByName.keys)
  return "OK"
}
