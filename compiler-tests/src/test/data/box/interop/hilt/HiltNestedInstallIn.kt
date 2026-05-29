// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP

// Verifies in-round Hilt `@Module` / `@EntryPoint` nested inside an enclosing class merge into
// the graph correctly.

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
