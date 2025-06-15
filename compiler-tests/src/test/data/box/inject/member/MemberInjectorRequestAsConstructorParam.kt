// https://github.com/ZacSweers/metro/discussions/593
class Foo {
  @Inject lateinit var message: String
}

@Inject class FooHolder(val fooInjector: MembersInjector<Foo>) {
  val foo = Foo().apply { fooInjector.injectMembers(this) }
}

@DependencyGraph
interface AppGraph {
  val fooHolder: FooHolder

  @Provides fun provideFoo(fooInjector: MembersInjector<Foo>): Foo {
    return Foo().apply { fooInjector.injectMembers(this) }
  }

  @Provides
  fun provideMessage(): String = "message"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val foo = graph.fooHolder.foo
  assertEquals("message", foo.message)
  return "OK"
}
