// MODULE: lib
// ENABLE_HILT_KSP
// WITH_DAGGER
// DISABLE_METRO
// FILE: MyHiltModule.kt
// Verifies Metro parses aggregation metadata produced by Hilt's real KSP processors.
package test

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
class MyHiltModule {
  @Provides fun provideMessage(): String = "Hello from Hilt KSP"
}

// MODULE: main(lib)
// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP

import javax.inject.Singleton

@DependencyGraph(Singleton::class)
interface AppGraph {
  val message: String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello from Hilt KSP", graph.message)
  return "OK"
}
