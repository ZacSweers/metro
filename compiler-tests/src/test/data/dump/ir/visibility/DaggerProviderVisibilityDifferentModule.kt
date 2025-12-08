// ENABLE_DAGGER_KSP
// Test direct invocation checks for internal Dagger provider factories in different module
// Internal dagger provider in different module should not support direct invocation

// MODULE: lib
// FILE: InternalModule.kt
package test

import dagger.Module
import dagger.Provides

@Module
class InternalModule {
  @Provides internal fun provideInt(): Int = 42
}

// MODULE: main(lib)
// FILE: ExampleGraph.kt
package test

@DependencyGraph(bindingContainers = [InternalModule::class])
interface ExampleGraph {
  val int: Int
}