// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package test

import dev.zacsweers.metro.*

// ======== ERROR DIAGNOSTIC ========
// Mismatched assisted factory parameters
@AssistedInject
class AssistedWithMismatchedParams(@Assisted val id: Int, @Assisted val name: String) {
  @AssistedFactory
  interface Factory {
    // ERROR: Missing 'name' param
    fun create(id: Int): AssistedWithMismatchedParams
  }
}

// ======== WARNING DIAGNOSTIC ========
// Should suggest moving @Inject to class level
class SuggestClassInject @Inject constructor(val dep: String)

// ======== GENERATED ASSISTED FACTORY ========
// Factory interface should be generated as a nested type
@AssistedInject
class AssistedWithGeneratedFactory(@Assisted val id: Int, val injectedDep: String) {
  // IDE should show generated:
  // fun interface Factory {
  //   fun create(id: Int): AssistedWithGeneratedFactory
  // }
}

// Test usage of generated factory
fun useGeneratedFactory(factory: AssistedWithGeneratedFactory.Factory) {
  val instance = factory.create(42)
}

// ======== GENERATED TOP-LEVEL FUNCTION ========
@Inject fun MyApp(@Assisted value: String, injected: Int) {}

fun useGeneratedApp(app: MyApp) {
  app(value = "app")
}

// ======== GENERATED SUPERTYPE ========
@ContributesTo(AppScope::class)
interface Base {
  val int: Int

  @Provides fun provideInt(): Int = 3
}

@DependencyGraph(AppScope::class) interface AppGraph

fun useGraphWithSupertype() {
  // Supertype is added
  createGraph<AppGraph>().int
}
