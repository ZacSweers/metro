@DependencyGraph
interface AppGraph {
  val strings: Set<String>
  val value: CharSequence
  val comparable: Comparable<String>

  @Multibinds(allowEmpty = true) private val privateInts: Set<Int> get() = error("Never called")
  val ints: Set<Int>

  @Binds private fun String.bind(): CharSequence = this
  @Binds @IntoSet private fun String.bindIntoSet(): String = this
  @Binds private val String.bind: Comparable<String> get() = this
  @Provides private fun provideString(): String = "Hello"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.value)
  assertEquals(setOf("Hello"), graph.strings)
  assertEquals(emptySet(), graph.ints)
  assertEquals("Hello", graph.comparable)
  return "OK"
}