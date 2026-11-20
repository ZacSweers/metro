package test

@DependencyGraph
interface ExampleGraph {
  @Multibinds
  val ints: Map<Int, Int>

  val exampleClass: ExampleClass

  @Provides @IntoMap @IntKey(0) fun provideInt0(): Int = 0
  @Provides @IntoMap @IntKey(1) fun provideInt1(): Int = 1
  @Provides @IntoMap @IntKey(2) fun provideInt2(): Int = 2
}

@Inject class ExampleClass(val ints: Map<Int, () -> Int>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val exampleClass = graph.exampleClass
  val ints = exampleClass.ints
  assertEquals(mapOf(0 to 0, 1 to 1, 2 to 2), ints.mapValues { (_, value) -> value() })
  return "OK"
}
