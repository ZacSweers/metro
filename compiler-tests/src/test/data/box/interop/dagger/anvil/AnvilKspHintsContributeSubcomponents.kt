// MODULE: lib
// ENABLE_ANVIL_KSP
// ANVIL_GENERATE_DAGGER_FACTORIES_ONLY: false
// DISABLE_METRO
// FILE: Subcomponents.kt
// Contributed subcomponents merge their factories into the graph for their parent scope.
package test

import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.ContributesTo
import dagger.Module
import dagger.Provides

abstract class LibScope

abstract class ChildScope

abstract class GrandchildScope

abstract class ExposedScope

@ContributesSubcomponent(scope = ChildScope::class, parentScope = LibScope::class)
interface ChildComponent {
  val message: String

  @ContributesSubcomponent.Factory
  interface Factory {
    fun createChild(): ChildComponent
  }
}

// This subcomponent's parent is another contributed subcomponent.
@ContributesSubcomponent(scope = GrandchildScope::class, parentScope = ChildScope::class)
interface GrandchildComponent {
  val number: Int

  @ContributesSubcomponent.Factory
  interface Factory {
    fun createGrandchild(): GrandchildComponent
  }
}

// Without a factory, a parent component interface exposes the subcomponent.
@ContributesSubcomponent(scope = ExposedScope::class, parentScope = LibScope::class)
interface ExposedComponent {
  val message: String

  @ContributesTo(LibScope::class)
  interface ParentComponent {
    fun exposedComponent(): ExposedComponent
  }
}

@Module
@ContributesTo(ChildScope::class)
object ChildModule {
  @Provides fun provideMessage(): String = "child"
}

@Module
@ContributesTo(GrandchildScope::class)
object GrandchildModule {
  @Provides fun provideNumber(): Int = 3
}

@Module
@ContributesTo(ExposedScope::class)
object ExposedModule {
  @Provides fun provideMessage(): String = "exposed"
}

// MODULE: main(lib)
// WITH_ANVIL
// ENABLE_DAGGER_INTEROP

import test.ChildComponent
import test.ExposedComponent
import test.GrandchildComponent
import test.LibScope

@DependencyGraph(LibScope::class)
interface AppGraph

@DependencyGraph(LibScope::class, excludes = [ChildComponent::class])
interface WithoutChildGraph

fun box(): String {
  val childFactory: ChildComponent.Factory = createGraph<AppGraph>()
  val child = childFactory.createChild()
  assertEquals("child", child.message)
  // Graph extensions merge contributions in IR, so only the generated class implements them.
  val grandchild = (child as GrandchildComponent.Factory).createGrandchild()
  assertEquals(3, grandchild.number)

  val parent = childFactory as ExposedComponent.ParentComponent
  assertEquals("exposed", parent.exposedComponent().message)

  assertFalse((createGraph<WithoutChildGraph>() as Any) is ChildComponent.Factory)
  return "OK"
}
