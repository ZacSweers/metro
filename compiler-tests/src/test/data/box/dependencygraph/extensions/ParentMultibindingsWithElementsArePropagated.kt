// Parent multibindings with elements should propagate to child extensions,
// merging with any child-contributed elements.
// https://github.com/ZacSweers/metro/pull/883

@DependencyGraph
interface AppGraph {
  val strings: Set<String>

  @Multibinds(allowEmpty = true) val ints: Set<Int>
  @IntoSet @Provides fun provideHello(): String = "hello"

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val strings: Set<String>
  val ints: Set<Int>

  @IntoSet @Provides fun provideWorld(): String = "world"
  @IntoSet @Provides fun provide1(): Int = 1

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = parent.childGraphFactory().create()
  assertEquals(setOf("hello", "world"), child.strings)
  assertEquals(setOf(1), child.ints)
  return "OK"
}
