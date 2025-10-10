// MODULE: lib
interface Providers {
  @Multibinds(allowEmpty = true) private val privateInts: Set<Int> get() = error("Never called")
  @Binds private fun String.bind(): CharSequence = this
  @Binds @IntoSet private fun String.bindIntoSet(): String = this
    @Binds private val String.bind: Comparable<String> get() = this
  @Provides private fun provideString(): String = "Hello"
}

// MODULE: main(lib)
@DependencyGraph
interface AppGraph : Providers {
  val strings: Set<String>
  val value: CharSequence
  val comparable: Comparable<String>
  val ints: Set<Int>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.value)
  assertEquals(setOf("Hello"), graph.strings)
  assertEquals(emptySet(), graph.ints)
  assertEquals("Hello", graph.comparable)
  return "OK"
}