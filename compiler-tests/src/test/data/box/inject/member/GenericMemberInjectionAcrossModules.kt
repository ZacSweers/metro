// MODULE: lib
@HasMemberInjections
abstract class Base<T : Any> {
  @Inject lateinit var value: T
}

// MODULE: main(lib)
class Sub : Base<String>()

@DependencyGraph
interface AppGraph {
  fun inject(sub: Sub)

  @Provides fun provideString(): String = "hello"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val sub = Sub()
  graph.inject(sub)
  assertEquals("hello", sub.value)
  return "OK"
}
