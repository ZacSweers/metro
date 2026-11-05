// Every consumer of Provider<Foo> should get the same provider instead of a new factory, when Foo
// comes from a @Provides function.

@Inject class Bar

class Foo(val bar: Bar)

@Inject class Consumer(val foo: Provider<Foo>)

@BindingContainer
object FooBindings {
  @Provides fun provideFoo(bar: Bar): Foo = Foo(bar)
}

@DependencyGraph(AppScope::class, bindingContainers = [FooBindings::class])
interface AppGraph {
  val consumer: Consumer
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertSame(graph.consumer.foo, graph.consumer.foo)
  return "OK"
}
