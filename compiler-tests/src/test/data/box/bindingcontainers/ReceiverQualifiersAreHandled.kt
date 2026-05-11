// https://github.com/ZacSweers/metro/discussions/2265
@DependencyGraph
interface AppGraph {
  val value: Number

  @Named("int") @Provides fun provideInt(): Int = 3
  @Binds fun @receiver:Named("int") Int.bindNumber(): Number
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.value)
  return "OK"
}