// https://github.com/ZacSweers/metro/issues/2639

// MODULE: lib
// FILE: ScopeAliases.kt
package aliases

import dev.zacsweers.metro.AppScope as TargetScope

typealias IntermediateAppScopeAlias = TargetScope
typealias AppScopeAlias = IntermediateAppScopeAlias

// FILE: Bindings.kt
package bindings

import aliases.AppScopeAlias

@ContributesTo(AppScopeAlias::class)
interface MyBindings {
  @Provides fun provideDependency(): Dependency = object : Dependency {}
}

interface Dependency

// MODULE: main(lib)
package app

import bindings.Dependency

@DependencyGraph(AppScope::class)
interface MyDependencyGraph {
  val dependency: Dependency
}

fun box(): String {
  assertNotNull(createGraph<MyDependencyGraph>().dependency)
  return "OK"
}
