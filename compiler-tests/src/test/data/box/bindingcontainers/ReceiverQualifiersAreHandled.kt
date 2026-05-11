// https://github.com/ZacSweers/metro/discussions/2265
@DependencyGraph
interface AppGraph {
  @Named("functionValue") val functionValue: Number
  @Named("propertyValue") val propertyValue: Number

  @Named("int") @Provides fun provideInt(): Int = 3
  @Named("functionValue") @Binds fun @receiver:Named("int") Int.bindNumber(): Number
  @Named("propertyValue") @Binds val @receiver:Named("int") Int.bindNumber: Number
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.functionValue)
  assertEquals(3, graph.propertyValue)
  return "OK"
}