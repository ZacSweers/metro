// ENABLE_DAGGER_KSP

// FILE: ExampleModule.kt
import dagger.Provides
import javax.inject.Named
import dagger.Module

@Module
class ExampleModule {
  @get:Named("qualifier") @get:Provides val example: String get() = "hello"
  @Provides fun intValue(): Int = 3
  // Dagger appears to ignore the JvmName in KSP
  @Provides @JvmName("longName") fun longValueWithJvmName(): Long = 3L
}

// FILE: ExampleGraph.kt
import javax.inject.Named

@DependencyGraph(bindingContainers = [ExampleModule::class])
interface ExampleGraph {
  @Named("qualifier") val value: String
  val int: Int
  val long: Long
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("hello", graph.value)
  assertEquals(3, graph.int)
  assertEquals(3L, graph.long)
  return "OK"
}
