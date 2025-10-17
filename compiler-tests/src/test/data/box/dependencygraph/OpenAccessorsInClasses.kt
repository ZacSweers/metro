abstract class Base {
  abstract val int: Int
}

@DependencyGraph
abstract class AppGraph : Base() {
  @Provides fun provideInt(): Int = 3
}

fun box(): String {
  assertEquals(3, createGraph<AppGraph>().int)
  return "OK"
}