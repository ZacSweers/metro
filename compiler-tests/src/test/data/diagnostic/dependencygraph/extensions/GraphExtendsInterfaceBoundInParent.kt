// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

abstract class Parent

interface Test

@GraphExtension(Parent::class)
interface ChildGraph : Test {

  val test: Test

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph : Test {
  @Binds val ExampleGraph.bind: Test
}
