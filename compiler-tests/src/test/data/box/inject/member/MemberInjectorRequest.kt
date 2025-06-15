// https://github.com/ZacSweers/metro/discussions/593
class Foo {
  @Inject lateinit var message: String
}

@DependencyGraph
interface AppGraph {
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
  return "OK"
}
