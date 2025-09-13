@DependencyGraph(AppScope::class)
interface AppGraph {
  // If the fun is named the same as val accessor, transformer shouldn't add override on this
  fun int(): Int

  // Transformer should add override when the field is inherited in the contributed type
  val string: String

  @Provides
  fun provideInt(): Int = 3

  @Provides
  fun provideString(): String = "str"
}

@ContributesTo(AppScope::class)
interface IntProvider : StringProvider {
  val int: Int
  fun string(): String
}

interface StringProvider {
  val string: String
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertEquals(appGraph.int(), 3)
  assertEquals(appGraph.int, 3)
  assertEquals(appGraph.string, "str")
  return "OK"
}