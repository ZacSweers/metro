// MODULE: lib
// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// FILE: UpstreamEntryPoint.kt
// Verifies Metro treats `@InstallIn` + `@EntryPoint` like `@ContributesTo` across module bounds.
package test

import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpstreamEntryPoint {
  val message: String
}

@Module
@InstallIn(SingletonComponent::class)
class UpstreamModule {
  @Provides fun provideMessage(): String = "Hello upstream"
}

// MODULE: main(lib)
// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP

import javax.inject.Singleton
import test.UpstreamEntryPoint

@DependencyGraph(Singleton::class)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  val entryPoint = graph as UpstreamEntryPoint
  assertEquals("Hello upstream", entryPoint.message)
  return "OK"
}
