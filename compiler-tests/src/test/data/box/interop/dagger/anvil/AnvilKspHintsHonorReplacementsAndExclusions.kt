// MODULE: lib
// ENABLE_ANVIL_KSP
// ANVIL_GENERATE_DAGGER_FACTORIES_ONLY: false
// DISABLE_METRO
// FILE: Contributions.kt
// Anvil's generated binding modules point back to their source class. Replacements and exclusions
// name that source class.
package test

import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesTo
import dagger.Module
import dagger.Provides
import javax.inject.Inject

abstract class LibScope

interface Greeter {
  fun greet(): String
}

@ContributesBinding(LibScope::class)
class RealGreeter @Inject constructor() : Greeter {
  override fun greet(): String = "real"
}

@ContributesBinding(LibScope::class, replaces = [RealGreeter::class])
class FakeGreeter @Inject constructor() : Greeter {
  override fun greet(): String = "fake"
}

@ContributesTo(LibScope::class)
interface ExcludedAccessor {
  val number: Int
}

@Module
@ContributesTo(LibScope::class)
object ExcludedModule {
  @Provides fun provideNumber(): Int = 1
}

// MODULE: main(lib)
// WITH_ANVIL
// ENABLE_DAGGER_INTEROP

import test.ExcludedAccessor
import test.ExcludedModule
import test.FakeGreeter
import test.Greeter
import test.LibScope

// A second Int binding here would clash with ExcludedModule if it were merged.
@DependencyGraph(LibScope::class, excludes = [ExcludedAccessor::class, ExcludedModule::class])
interface AppGraph {
  val greeter: Greeter
  val number: Int

  @Provides fun provideNumber(): Int = 2
}

// Excluding FakeGreeter also drops its replacement of RealGreeter.
@DependencyGraph(LibScope::class, excludes = [FakeGreeter::class])
interface WithoutFakeGraph {
  val greeter: Greeter
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("fake", graph.greeter.greet())
  assertEquals(2, graph.number)
  assertFalse((graph as Any) is ExcludedAccessor)
  val withoutFake = createGraph<WithoutFakeGraph>()
  assertEquals("real", withoutFake.greeter.greet())
  assertEquals(1, (withoutFake as ExcludedAccessor).number)
  return "OK"
}
