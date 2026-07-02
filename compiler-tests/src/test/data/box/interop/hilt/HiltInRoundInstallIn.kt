// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP

// Verifies Hilt modules and entry points declared in the same compilation as the graph.

import dagger.Module
import dagger.Provides as DaggerProvides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class MyHiltModule {
  @DaggerProvides fun provideMessage(): String = "Hello in-round"
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MyEntryPoint {
  val message: String
}

@DependencyGraph(Singleton::class)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  val entryPoint = graph as MyEntryPoint
  assertEquals("Hello in-round", entryPoint.message)
  return "OK"
}
