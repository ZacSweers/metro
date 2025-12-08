// ENABLE_DAGGER_KSP
// MODULE: lib
import dagger.Module
import dagger.Provides

@Module
class ValuesModule {
  @get:Provides val provideLong: Long get() = 3L
  @get:Provides val provideInt: Int = 3
  @get:Provides val isEnabled: Boolean = true
  @get:Provides @get:JvmName("aString") val stringValue: String = "hello"
  @get:Provides @get:JvmName("aDouble") val doubleValue: Double get() = 3.0
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
@DependencyGraph(AppScope::class, bindingContainers = [ValuesModule::class])
interface AppGraph {
  val int: Int
  val long: Long
  val double: Double
  val string: String
  val isEnabled: Boolean
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.int)
  assertEquals(3L, graph.long)
  assertEquals(3.0, graph.double)
  assertEquals("hello", graph.string)
  assertTrue(graph.isEnabled)
  return "OK"
}
