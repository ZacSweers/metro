// Regression test to ensure overloaded binds callables stored in metadata are still all found
// MODULE: lib
interface Bindings {
  @Binds val String.bind: CharSequence
  @Binds val Int.bind: Number
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph : Bindings {
  val charSequence: CharSequence
  val number: Number

  @Provides fun provideString(): String = "Hello"
  @Provides fun provdeInt(): Int = 3
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.charSequence)
  assertEquals(3, graph.number)
  return "OK"
}