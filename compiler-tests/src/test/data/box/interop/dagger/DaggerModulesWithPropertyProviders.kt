// ENABLE_DAGGER_KSP
// MODULE: lib
import dagger.Module
import dagger.Provides

@Module
class ValuesModule {
  @get:Provides val provideLong: Long get() = 3L
  @get:Provides val provideInt: Int = 3
  @get:Provides val isEnabled: Boolean = true
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
@DependencyGraph(AppScope::class, bindingContainers = [ValuesModule::class])
interface AppGraph {
  val int: Int
  val long: Long
  val isEnabled: Boolean
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.int)
  assertEquals(3L, graph.long)
  assertTrue(graph.isEnabled)
  return "OK"
}
