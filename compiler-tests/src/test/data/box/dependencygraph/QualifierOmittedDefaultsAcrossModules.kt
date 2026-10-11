// MODULE: lib
// FILE: Values.kt

@Qualifier
annotation class ScalarKey(val value: String = "default")

annotation class Nested(val value: String = "nested")

@Qualifier
annotation class NestedKey(val value: Nested)

@BindingContainer
object Values {
  @Provides @ScalarKey fun binary(): String = "binary"
  @Provides @NestedKey(Nested()) fun nested(): Long = 42L
}

// MODULE: main(lib)
// FILE: main.kt

// Matching omissions remain usable when dependency defaults aren't available.
@DependencyGraph(bindingContainers = [Values::class])
interface AppGraph {
  @ScalarKey val binary: String
  @ScalarKey val source: Int
  @NestedKey(Nested()) val nested: Long

  companion object {
    @Provides @ScalarKey fun source(): Int = 1
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("binary", graph.binary)
  assertEquals(1, graph.source)
  assertEquals(42L, graph.nested)
  return "OK"
}
