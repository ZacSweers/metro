// RENDER_DIAGNOSTICS_FULL_TEXT
// https://github.com/ZacSweers/metro/issues/774
// MODULE: lib

abstract class ChildScope

@GraphExtension(ChildScope::class)
interface ChildGraph {

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph

@Inject
class Foo

@ContributesTo(ChildScope::class)
interface FooProvider {
  val foo: Foo
}

fun example() {
  val appGraph = createGraph<AppGraph>()
  val childGraph = appGraph.asContribution<ChildGraph.Factory>().create()
  val foo = <!AS_CONTRIBUTION_ERROR!>childGraph<!>.asContribution<FooProvider>().foo
}