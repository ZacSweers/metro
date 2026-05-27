// MODULE: lib
// ENABLE_HILT_KSP
// DISABLE_METRO
// FILE: MyEntryPoint.kt
// Hilt's KSP processors generate the `hilt_aggregated_deps/...` marker for the entry point on
// lib's classpath; main consumes it via Metro's Hilt interop.
package test

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MyEntryPoint {
  val message: String
}

// MODULE: main(lib)
// ENABLE_HILT_INTEROP

import javax.inject.Singleton
import test.MyEntryPoint

@DependencyGraph(Singleton::class)
interface AppGraph {
  @Provides fun provideMessage(): String = "Hello entry point"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val entryPoint = graph as MyEntryPoint
  assertEquals("Hello entry point", entryPoint.message)
  return "OK"
}
