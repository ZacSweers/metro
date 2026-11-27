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

// The parent only offers Bar through its unscoped @Inject constructor. Every graph builds its own.
@Inject open class Bar

@Inject class ChildBar : Bar()

@GraphExtension
interface InjectParentChildGraph {
  val bar: Bar

  @Binds val ChildBar.bind: Bar
}

@DependencyGraph
interface InjectParentGraph {
  val bar: Bar
  val childGraph: InjectParentChildGraph
}

// OwnScoped belongs to the child's own scope, so no ancestor keeps an instance of it.
abstract class OwnScope private constructor()

@SingleIn(OwnScope::class) @Inject open class OwnScoped

@Inject class OtherOwnScoped : OwnScoped()

@GraphExtension(OwnScope::class)
interface OwnScopeChildGraph {
  val ownScoped: OwnScoped

  @Binds val OtherOwnScoped.bind: OwnScoped
}

@DependencyGraph
interface OwnScopeGraph {
  val childGraph: OwnScopeChildGraph
}

// The parent's private input wins over Baz's scoped @Inject constructor there. The child inherits
// neither one.
abstract class PrivateInputScope private constructor()

@SingleIn(PrivateInputScope::class) @Inject open class Baz

@Inject class ChildBaz : Baz()

@Inject class BazConsumer(val baz: Baz)

@GraphExtension
interface PrivateInputChildGraph {
  val baz: Baz

  @Binds val ChildBaz.bind: Baz
}

@DependencyGraph(PrivateInputScope::class)
interface PrivateInputGraph {
  val consumer: BazConsumer
  val childGraph: PrivateInputChildGraph

  @DependencyGraph.Factory
  interface Factory {
    fun create(@GraphPrivate @Provides baz: Baz): PrivateInputGraph
  }
}

// METRO_JVM_ONLY
