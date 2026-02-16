// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package test

import dev.zacsweers.metro.*

// METRO_DIAGNOSTIC: ASSISTED_INJECTION_ERROR,ERROR,AssistedWithMismatchedParams factory is missing
// 'name' parameter
@AssistedInject
class AssistedWithMismatchedParams(@Assisted val id: Int, @Assisted val name: String) {
  @AssistedFactory
  interface Factory {
    fun create(id: Int): AssistedWithMismatchedParams
  }
}

// METRO_DIAGNOSTIC: SUGGEST_CLASS_INJECTION,WARNING,SuggestClassInject has @Inject on constructor
// should be on class
class SuggestClassInject @Inject constructor(val dep: String)

// METRO_INLAY: AssistedFactory
@AssistedInject
class AssistedWithGeneratedFactory(@Assisted val id: Int, val injectedDep: String) {
  // IDE inlay shows a generated assisted factory here
}

// Test usage of generated factory
fun useGeneratedFactory(factory: AssistedWithGeneratedFactory.Factory) {
  val instance = factory.create(42)
}

// Generate and use a top-level injected function class
@Inject fun MyApp(@Assisted value: String, injected: Int) {}

fun useGeneratedApp(app: MyApp) {
  app(value = "app")
}

// Viewing generated supertypes
@ContributesTo(AppScope::class)
interface Base {
  val int: Int

  @Provides fun provideInt(): Int = 3
}

// METRO_INLAY: MetroContributionToAppScope
@DependencyGraph(AppScope::class) interface AppGraph

fun useGraphWithSupertype() {
  // Supertype is added so this resolves
  createGraph<AppGraph>().int
}
