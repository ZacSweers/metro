// MODULE: lib
// ENABLE_HILT_KSP
// DISABLE_METRO
// FILE: MyHiltModule.kt
// Hilt's KSP processors generate the `hilt_aggregated_deps/...` marker and the per-@Provides
// Dagger factory classes on lib's classpath; main consumes them via Metro's Hilt interop.
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
