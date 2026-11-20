// CHECK_BYTECODE_TEXT
// MODULE: lib
// FILE: Library.kt

@ContributesTo(AppScope::class)
internal interface ContributedInterface

fun hasContribution(value: Any): Boolean = value is ContributedInterface

// 0 class metro/hints/MetroHintsContributedInterfaceAppScopeKt

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface ExampleGraph

fun box(): String {
  assertFalse(hasContribution(createGraph<ExampleGraph>()))
  return "OK"
}
