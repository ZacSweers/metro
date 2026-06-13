// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP

// Verifies nested Hilt modules and entry points declared in the same compilation as the graph.

import dagger.Module
import dagger.Provides as DaggerProvides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

class Outer {
  @Module
  @InstallIn(SingletonComponent::class)
  class NestedModule {
    @DaggerProvides fun provideMessage(): String = "Hello nested"
  }

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface NestedEntryPoint {
    val message: String
  }
}

@DependencyGraph(Singleton::class)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  val entryPoint = graph as Outer.NestedEntryPoint
  assertEquals("Hello nested", entryPoint.message)
  return "OK"
}
