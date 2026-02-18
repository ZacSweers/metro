// Ignored but left as a toe-hold. anvil-ksp doesn't support generic modules
// METRO_IGNORE
// MODULE: lib
// ENABLE_ANVIL_KSP
// DISABLE_METRO
// FILE: GenericModule.kt
import dagger.Module
import dagger.Provides

@Module
abstract class GenericModule<T>(private val value: T) {
  @Provides fun provideString(): String = value.toString()
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
@DependencyGraph
interface AppGraph {
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes module: GenericModule<Int>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(object : GenericModule<Int>(42) {})
  assertEquals("42", graph.string)
  return "OK"
}
