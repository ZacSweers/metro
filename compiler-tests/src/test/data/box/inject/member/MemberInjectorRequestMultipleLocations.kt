// https://github.com/ZacSweers/metro/discussions/593
@Inject // Trigger automatic creation of a MembersInjector binding
class Foo {
  @Inject lateinit var message: String
}

@Inject class FooHolder(val injector: MembersInjector<Foo>)

@DependencyGraph
interface AppGraph {
  // Trigger creation of the Foo binding
  val foo: Foo
  val fooHolder: FooHolder

  // Trigger creation for the accessor
  val fooInjector: MembersInjector<Foo>

  @Provides
  fun provideMessage(): String = "message"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val injector = graph.fooInjector
  val foo = Foo()
  injector.injectMembers(foo)
  assertEquals("message", foo.message)

  val otherFoo = Foo()
  graph.fooHolder.injector.injectMembers(otherFoo)
  assertEquals("message", otherFoo.message)
  return "OK"
}
