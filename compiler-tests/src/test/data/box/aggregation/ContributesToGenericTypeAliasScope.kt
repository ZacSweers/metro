// MODULE: lib
// FILE: Scopes.kt
package scopes

abstract class Scope<T> {
  abstract class Nested<U>
}

// FILE: ScopeAliases.kt
package aliases

import scopes.Scope as ScopeClass
import scopes.Scope.Nested as NestedScopeClass

typealias GenericScope<T> = ScopeClass<List<T>>
typealias ScopedStrings = GenericScope<String>
typealias ChainedStringsScope = ScopedStrings
typealias NestedStringsScope = NestedScopeClass<Map<String, List<Int>>>
typealias Callback = (String) -> Int

// FILE: Bindings.kt
package bindings

import aliases.ChainedStringsScope as ImportedScopeAlias
import aliases.NestedStringsScope
import aliases.Callback

@ContributesTo(ImportedScopeAlias::class)
interface GenericBindings {
  @Provides fun provideValue(): String = "generic"
}

@ContributesTo(NestedStringsScope::class)
interface NestedBindings {
  @Provides fun provideValue(): String = "nested"
}

@ContributesTo(Callback::class)
interface CallbackBindings {
  @Provides fun provideValue(): String = "callback"
}

// MODULE: main(lib)
package app

import scopes.Scope as ScopeClass
import scopes.Scope.Nested as NestedScopeClass
import aliases.Callback

// Generic aliases contribute to the underlying scope class, including nested classifiers.
@DependencyGraph(ScopeClass::class)
interface GenericGraph {
  val value: String
}

@DependencyGraph(NestedScopeClass::class)
interface NestedGraph {
  val value: String
}

// Function aliases have no class-name qualifiers during early contribution discovery.
@DependencyGraph(Callback::class)
interface CallbackGraph {
  val value: String
}

fun box(): String {
  assertEquals("generic", createGraph<GenericGraph>().value)
  assertEquals("nested", createGraph<NestedGraph>().value)
  assertEquals("callback", createGraph<CallbackGraph>().value)
  return "OK"
}
