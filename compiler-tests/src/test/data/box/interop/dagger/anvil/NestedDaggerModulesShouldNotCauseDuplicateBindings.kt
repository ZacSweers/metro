// ENABLE_DAGGER_INTEROP
// WITH_ANVIL
// Tests that Dagger @Module classes nested inside a component class don't cause duplicate bindings.
// When a @ContributesTo @Module is nested inside another class (like a component),
// it should only be contributed once, not twice.
//
// This is a regression test for a bug where nested modules caused duplicate binding errors.
// The bug manifests in two ways depending on the component hierarchy:
// 1. "Multiple bindings found for X ... (Hint) Bindings are all equal" - when both duplicate
//    bindings have distinct reportable locations
// 2. "Must have at least two locations to report duplicate bindings" - when the same binding
//    is picked up twice but only one location is recorded (same source, different lookup paths)

import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeComponent
import com.squareup.anvil.annotations.MergeSubcomponent
import dagger.Binds
import dagger.Module

interface Repository

class RepositoryImpl @Inject constructor() : Repository

@Qualifier
annotation class QualifiedRepository

sealed interface ChildScope
sealed interface GrandchildScope

class ChildValue
class GrandchildValue

// Parent component
@MergeComponent(AppScope::class)
interface ParentComponent {
  val childFactory: ChildComponent.Factory
}

// Child component with NESTED Dagger modules
@MergeSubcomponent(ChildScope::class)
interface ChildComponent {

  val childValue: ChildValue

  val grandchildFactory: GrandchildComponent.Factory

  @GraphExtension.Factory
  @MergeSubcomponent.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun create(@Provides childValue: ChildValue): ChildComponent
  }

  // Nested Dagger module with @ContributesTo
  @ContributesTo(ChildScope::class)
  @Module
  object ChildModule {
    @Provides
    fun provideRepositoryImpl(): RepositoryImpl {
      return RepositoryImpl()
    }
  }

  // Nested interface module with @Binds
  @ContributesTo(ChildScope::class)
  @Module
  interface ChildBindings {
    @Binds
    @QualifiedRepository
    fun bindRepository(impl: RepositoryImpl): Repository
  }
}

// Grandchild component that uses the bound repository
@ContributesSubcomponent(GrandchildScope::class, parentScope = ChildScope::class)
interface GrandchildComponent {

  @QualifiedRepository
  val qualifiedRepository: Repository

  @GraphExtension.Factory
  @ContributesTo(ChildScope::class)
  interface Factory {
    fun create(@Provides grandchildValue: GrandchildValue): GrandchildComponent
  }
}

fun box(): String {
  val parent = createGraph<ParentComponent>()
  val child = parent.childFactory.create(ChildValue())
  val grandchild = child.grandchildFactory.create(GrandchildValue())

  assertNotNull(child.childValue)
  assertNotNull(grandchild.qualifiedRepository)

  return "OK"
}
