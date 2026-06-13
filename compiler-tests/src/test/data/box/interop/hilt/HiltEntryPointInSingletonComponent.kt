// MODULE: lib
// ENABLE_HILT_KSP
// DISABLE_METRO
// FILE: MyEntryPoint.kt
// Verifies entry points discovered from Hilt-generated aggregation metadata.
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
