// RENDER_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
// ENABLE_ANVIL_KSP
// ANVIL_GENERATE_DAGGER_FACTORIES_ONLY: false
// DISABLE_METRO
// FILE: Subcomponents.kt
// Metro can't add the accessor that Anvil generates for a subcomponent without a factory or parent
// component interface, so graphs that would merge one report an error.
package test

import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.ContributesTo

abstract class LibScope

abstract class ChildScope

abstract class OrphanScope

abstract class NestedOrphanScope

abstract class ExposedScope

@ContributesSubcomponent(scope = OrphanScope::class, parentScope = LibScope::class)
interface OrphanComponent

// Reachable through its factory. Its own scope has a subcomponent that isn't reachable.
@ContributesSubcomponent(scope = ChildScope::class, parentScope = LibScope::class)
interface ChildComponent {
  @ContributesSubcomponent.Factory
  interface Factory {
    fun createChild(): ChildComponent
  }
}

@ContributesSubcomponent(scope = NestedOrphanScope::class, parentScope = ChildScope::class)
interface NestedOrphanComponent

// Reachable through its parent component interface.
@ContributesSubcomponent(scope = ExposedScope::class, parentScope = LibScope::class)
interface ExposedComponent {
  @ContributesTo(LibScope::class)
  interface ParentComponent {
    fun exposedComponent(): ExposedComponent
  }
}

// MODULE: main(lib)
// WITH_ANVIL
// ENABLE_DAGGER_INTEROP

import test.LibScope
import test.NestedOrphanComponent
import test.OrphanComponent

@DependencyGraph(LibScope::class)
interface <!AGGREGATION_ERROR!>AppGraph<!>

@DependencyGraph(LibScope::class, excludes = [OrphanComponent::class, NestedOrphanComponent::class])
interface ExcludingGraph
