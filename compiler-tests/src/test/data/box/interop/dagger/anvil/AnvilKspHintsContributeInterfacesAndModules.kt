// MODULE: lib
// ENABLE_ANVIL_KSP
// ANVIL_GENERATE_DAGGER_FACTORIES_ONLY: false
// DISABLE_METRO
// FILE: Contributions.kt
// Verifies Metro merges contributions it finds through Anvil's classpath hints.
package test

import com.squareup.anvil.annotations.ContributesTo
import dagger.Module
import dagger.Provides

abstract class LibScope

@ContributesTo(LibScope::class)
interface MessageAccessor {
  val message: String
}

@Module
@ContributesTo(LibScope::class)
object MessageModule {
  @Provides fun provideMessage(): String = "Hello from Anvil"
}

// MODULE: main(lib)
// WITH_ANVIL
// ENABLE_DAGGER_INTEROP

import test.LibScope
import test.MessageAccessor

@DependencyGraph(LibScope::class)
interface AppGraph

@MergeContributionsInIr
@DependencyGraph(LibScope::class)
interface IrMergedGraph

@GraphExtension(LibScope::class)
interface ChildGraph

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val childGraph: ChildGraph
}

fun box(): String {
  val accessor: MessageAccessor = createGraph<AppGraph>()
  assertEquals("Hello from Anvil", accessor.message)
  val irAccessor = createGraph<IrMergedGraph>() as MessageAccessor
  assertEquals("Hello from Anvil", irAccessor.message)
  // Graph extensions merge contributions in IR, so only the generated class implements them.
  val childAccessor = createGraph<ParentGraph>().childGraph as MessageAccessor
  assertEquals("Hello from Anvil", childAccessor.message)
  return "OK"
}
