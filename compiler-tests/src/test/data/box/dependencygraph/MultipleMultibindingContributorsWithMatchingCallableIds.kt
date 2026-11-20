package test

@DependencyGraph
interface ExampleGraph : ContributingInterface1, ContributingInterface2 {
  val strings: Set<String>

  @Provides
  val provideInt: Int get() = 1

  @Binds
  val Int.provideString: Number

  @Provides
  @IntoSet
  val provideString: String get() = "0"

}

interface ContributingInterface1 {
  @Provides
  @IntoSet
  fun provideString(int: Int): String = int.toString()
}

interface ContributingInterface2 {
  @Provides
  @IntoSet
  fun provideString(number: Number): String {
    // Resolves to 1 + 2 = 3
    return (number.toInt() + 2).toString()
  }
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val strings = graph.strings
  assertEquals(setOf("0", "1", "3"), strings)
  return "OK"
}
