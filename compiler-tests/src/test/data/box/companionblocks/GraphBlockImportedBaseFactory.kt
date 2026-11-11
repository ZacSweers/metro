// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

// FILE: Bases.kt
package bases

interface StarFactory<G> {
  fun create(@Provides value: String): G
}

interface AliasedFactory<G> {
  fun build(@Provides value: Int): G
}

// FILE: main.kt
import bases.*
import bases.AliasedFactory as RenamedFactory

@DependencyGraph
interface StarGraph {
  val value: String

  @DependencyGraph.Factory fun interface Factory : StarFactory<StarGraph>
}

@DependencyGraph
interface AliasGraph {
  val value: Int

  @DependencyGraph.Factory fun interface Factory : RenamedFactory<AliasGraph>
}

fun box(): String {
  assertEquals("x", StarGraph.create("x").value)
  assertEquals(3, AliasGraph.build(3).value)
  return "OK"
}
