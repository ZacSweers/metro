// RUN_PIPELINE_TILL: BACKEND
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

package parity.extension.overrideexclusions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.GraphPrivate
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

// Each pair of graphs declares one key at both levels in a way that isn't an override. Any override
// report here would be an error.

// A binding container that both levels include declares the same binding twice.
@BindingContainer
object SharedBindings {
  @Provides fun provideString(): String = "shared"
}

@GraphExtension(bindingContainers = [SharedBindings::class])
interface SharedContainerChildGraph {
  val string: String
}

@DependencyGraph(bindingContainers = [SharedBindings::class])
interface SharedContainerGraph {
  val string: String
  val childGraph: SharedContainerChildGraph
}

// Both graphs inherit the provider from one supertype.
interface SharedProviders {
  @Provides fun provideInt(): Int = 1
}

@GraphExtension
interface SharedSupertypeChildGraph : SharedProviders {
  val int: Int
}

@DependencyGraph
interface SharedSupertypeGraph : SharedProviders {
  val int: Int
  val childGraph: SharedSupertypeChildGraph
}

// The child never inherits a @GraphPrivate binding.
class Foo(val value: String)

@Inject class PrivateConsumer(val long: Long, val foo: Foo)

@GraphExtension
interface PrivateParentChildGraph {
  val long: Long
  val foo: Foo

  @Provides fun provideLong(): Long = 2L

  @Provides fun provideFoo(): Foo = Foo("child")
}

@DependencyGraph(AppScope::class)
interface PrivateParentGraph {
  val consumer: PrivateConsumer
  val childGraph: PrivateParentChildGraph

  @GraphPrivate @Provides fun provideLong(): Long = 1L

  @GraphPrivate @SingleIn(AppScope::class) @Provides fun provideFoo(): Foo = Foo("parent")
}

// The parent only offers Bar through its @Inject constructor.
abstract class InjectScope private constructor()

@SingleIn(InjectScope::class) @Inject open class Bar

@Inject class ChildBar : Bar()

@GraphExtension
interface InjectParentChildGraph {
  val bar: Bar

  @Binds val ChildBar.bind: Bar
}

@SingleIn(InjectScope::class)
@DependencyGraph
interface InjectParentGraph {
  val bar: Bar
  val childGraph: InjectParentChildGraph
}

// METRO_JVM_ONLY
