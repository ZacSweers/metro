// https://github.com/ZacSweers/metro/discussions/593
class Foo<T : Any> {
  @Inject lateinit var message: T
}

@DependencyGraph
interface AppGraph {
  val fooInjector: MembersInjector<Foo<String>>

  @Provides
  fun provideMessage(): String = "message"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val injector = graph.fooInjector
  val foo = Foo<String>()
  injector.injectMembers(foo)
  assertEquals("message", foo.message)
  return "OK"
}
