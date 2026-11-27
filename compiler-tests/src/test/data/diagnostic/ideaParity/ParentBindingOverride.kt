// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR
// CHECK_REPORTS: keys-populated/parity/failures/parentoverride/AppGraph/Impl/ChildGraphImpl

package parity.failures.parentoverride

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

abstract class ChildScope private constructor()

interface Greeter

object ParentGreeter : Greeter

class Foo(val value: String)

interface StringDep {
  val string: String
}

// Each kind of ChildGraph declaration overrides one kind of inherited declaration.

@ContributesBinding(ChildScope::class) @Inject class <!PARENT_BINDING_OVERRIDE_ERROR!>ChildGreeter<!> : Greeter

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val string: String
  val chars: CharSequence
  val int: Int
  val foo: Foo
  val greeter: Greeter

  // Overrides the accessor of the graph that AppGraph includes.
  @Provides fun <!PARENT_BINDING_OVERRIDE_ERROR!>provideString<!>(): String = "child"

  @Binds <!PARENT_BINDING_OVERRIDE_ERROR!>val String.bind: CharSequence<!>

  // Overrides a scoped binding that this graph would read from AppGraph.
  @Provides fun <!PARENT_BINDING_OVERRIDE_ERROR!>provideFoo<!>(): Foo = Foo("child")

  @GraphExtension.Factory
  interface Factory {
    fun create(<!PARENT_BINDING_OVERRIDE_ERROR!>@Provides int: Int<!>): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String
  val chars: CharSequence
  val int: Int
  val foo: Foo
  val greeter: Greeter

  @Provides fun provideChars(): CharSequence = "parent"

  @Provides fun provideInt(): Int = 1

  @SingleIn(AppScope::class) @Provides fun provideFoo(): Foo = Foo("parent")

  @Provides fun provideGreeter(): Greeter = ParentGreeter

  fun childGraphFactory(): ChildGraph.Factory

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes dep: StringDep): AppGraph
  }
}

// METRO_JVM_ONLY
