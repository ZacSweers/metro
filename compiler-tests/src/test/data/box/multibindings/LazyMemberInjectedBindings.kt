@DependencyGraph
interface AppGraph {
  fun inject(target: ExampleClass)

  @Provides @IntoSet fun provideInt(): Int = 3
  @Provides @IntoMap @IntKey(3) fun provideIntIntoMap(): Int = 3
}

class ExampleClass {
  @Inject lateinit var intSet: Lazy<Set<Int>>
  @Inject lateinit var intMap: Lazy<Map<Int, Int>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val instance = ExampleClass()
  graph.inject(instance)
  assertEquals(setOf(3), instance.intSet.value)
  assertEquals(mapOf(3 to 3), instance.intMap.value)
  return "OK"
}