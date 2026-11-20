package test

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  // This is fine
  @ForScope(AppScope::class)
  val int: Int

  val myClass: MyClass
}

@ContributesTo(AppScope::class)
interface ContributedInterface {
  @Provides @ForScope(AppScope::class) fun provideInt(): Int = 2
}

class MyClass @Inject constructor(
  // This fails
  @ForScope(AppScope::class)
  val int: Int
)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(2, graph.int)
  assertEquals(2, graph.myClass.int)
  return "OK"
}
