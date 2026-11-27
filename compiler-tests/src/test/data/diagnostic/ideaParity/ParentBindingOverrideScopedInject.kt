// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

package parity.failures.parentoverridescopedinject

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

// No graph declares a scoped @Inject class. The ancestor that owns its scope keeps the one
// instance, so GrandchildGraph's bindings override Foo from AppGraph and Bar from ChildGraph. No
// ancestor requests either class.

abstract class ChildScope private constructor()

@SingleIn(AppScope::class) @Inject open class Foo

@Inject class GrandchildFoo : Foo()

@SingleIn(ChildScope::class) @Inject open class Bar

@GraphExtension
interface GrandchildGraph {
  val foo: Foo
  val bar: Bar

  @Binds <!PARENT_BINDING_OVERRIDE_ERROR!>val GrandchildFoo.bind: Foo<!>

  @GraphExtension.Factory
  interface Factory {
    fun create(<!PARENT_BINDING_OVERRIDE_ERROR!>@Provides bar: Bar<!>): GrandchildGraph
  }
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  fun grandchildGraphFactory(): GrandchildGraph.Factory

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  fun childGraphFactory(): ChildGraph.Factory
}

// METRO_JVM_ONLY
