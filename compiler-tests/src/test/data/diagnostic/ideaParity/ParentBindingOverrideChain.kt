// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

package parity.failures.parentoverridechain

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.GraphPrivate
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides

// Only GrandchildGraph reports. ChildGraph doesn't declare String, so that override names AppGraph.
// AppGraph keeps its Int private, so ChildGraph's Int isn't an override. GrandchildGraph's Int
// override names ChildGraph, the nearest ancestor that declares Int.

@Inject class IntConsumer(val int: Int)

@GraphExtension
interface GrandchildGraph {
  val string: String
  val int: Int

  @Provides fun <!PARENT_BINDING_OVERRIDE_ERROR!>provideString<!>(): String = "grandchild"

  @Provides fun <!PARENT_BINDING_OVERRIDE_ERROR!>provideInt<!>(): Int = 3

  @GraphExtension.Factory
  interface Factory {
    fun create(): GrandchildGraph
  }
}

@GraphExtension
interface ChildGraph {
  val string: String
  val int: Int

  fun grandchildGraphFactory(): GrandchildGraph.Factory

  @GraphExtension.Factory
  interface Factory {
    fun create(@Provides int: Int): ChildGraph
  }
}

@DependencyGraph
interface AppGraph {
  val string: String
  val consumer: IntConsumer

  @Provides fun provideString(): String = "root"

  @GraphPrivate @Provides fun provideInt(): Int = 1

  fun childGraphFactory(): ChildGraph.Factory
}

// METRO_JVM_ONLY
