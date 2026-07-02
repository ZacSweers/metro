// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// MODULE: lib
package test

internal interface MyScope

@DependencyGraph(MyScope::class)
interface CommonGraph

interface Foo

internal interface Bar

@ContributesBinding(MyScope::class)
@Inject
internal class FooImpl : Foo

@ContributesBinding(MyScope::class)
@Inject
internal class BarImpl : Bar

// MODULE: main(lib)
package test1

internal interface MyScope

@DependencyGraph(MyScope::class)
interface FeatureGraph


fun box(): String {
  return "OK"
}
