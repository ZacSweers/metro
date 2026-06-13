// MODULE: lib
// ENABLE_HILT_KSP
// DISABLE_METRO
// FILE: MyHiltModule.kt
// Verifies modules discovered from Hilt-generated aggregation metadata.
package test

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
class MyHiltModule {
  @Provides fun provideMessage(): String = "Hello from Hilt"
}

// MODULE: main(lib)
// ENABLE_HILT_INTEROP

import javax.inject.Singleton

@DependencyGraph(Singleton::class)
interface AppGraph {
  val message: String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello from Hilt", graph.message)
  return "OK"
}
