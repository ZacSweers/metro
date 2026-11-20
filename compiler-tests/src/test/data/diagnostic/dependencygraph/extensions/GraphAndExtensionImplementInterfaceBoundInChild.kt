// RUN_PIPELINE_TILL: BACKEND

abstract class ChildScope

interface Test

@GraphExtension(ChildScope::class)
interface ChildGraph : Test {

  val test: Test
  @Binds val ChildGraph.bind: Test

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph : Test {
}
