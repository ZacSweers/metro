// https://github.com/ZacSweers/metro/discussions/593
class Foo {
  @Inject lateinit var message: String
}

@DependencyGraph
interface AppGraph {
  val foo: Foo

  @Provides fun provideFoo(fooInjector: MembersInjector<Foo>): Foo {
    return Foo().apply { fooInjector.injectMembers(this) }
  }

  @Provides
  fun provideMessage(): String = "message"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val foo = graph.foo
  assertEquals("message", foo.message)
  return "OK"
}
