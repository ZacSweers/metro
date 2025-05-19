import kotlin.test.*

interface Foo

@Inject class Bar(val fooProvider: Provider<Foo>) : Foo

/**
 * A component with a cycle in which a `@Binds` binding depends on the binding that has to be
 * deferred.
 */
@DependencyGraph
interface BindsCycleGraph {
  fun bar(): Bar

  @Binds fun foo(bar: Bar): Foo
}

/**
 * Tests that a cycle where a `@Binds` binding depends on a binding that has to be deferred works.
 */
fun box(): String {
  val bindsCycleGraph = createGraph<BindsCycleGraph>()
  assertNotNull(bindsCycleGraph.bar())
  return "OK"
}