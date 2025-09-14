@DependencyGraph(AppScope::class)
interface AppGraph {
  // If the fun is named the same as val accessor, transformer shouldn't add override on this
  fun int(): Int

  // Transformer should add override when the field is inherited in the contributed type
  val string: String

  // Transformer should differentiate function and property accessors, and add override here
  val boolean: Boolean

  @Provides
  fun provideInt(): Int = 3

  @Provides
  fun provideString(): String = "str"

  @Provides
  fun provideBoolean(): Boolean = true
}

@ContributesTo(AppScope::class)
interface ChildProvider : ParentProvider {
  val int: Int
  fun boolean(): Boolean
}

interface ParentProvider {
  val string: String
  val boolean: Boolean
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertEquals(appGraph.int(), 3)
  assertEquals(appGraph.int, 3)
  assertEquals(appGraph.string, "str")
  assertEquals(appGraph.boolean(), true)
  assertEquals(appGraph.boolean, true)
  return "OK"
}