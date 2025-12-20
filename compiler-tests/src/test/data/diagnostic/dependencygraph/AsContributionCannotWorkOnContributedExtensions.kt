// RENDER_DIAGNOSTICS_FULL_TEXT
// https://github.com/ZacSweers/metro/issues/774
abstract class ChildScope

@GraphExtension(ChildScope::class)
interface ChildGraph {

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph

@Inject
class Foo

@ContributesTo(ChildScope::class)
interface FooProvider {
  val foo: Foo
}