// Empty @Multibinds Map declarations from a parent graph should propagate
// to child extensions, similar to Set multibinds.
// https://github.com/ZacSweers/metro/pull/883

@DependencyGraph
interface AppGraph {
  @Multibinds(allowEmpty = true) val strings: Map<String, String>

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val strings: Map<String, String>

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = parent.childGraphFactory().create()
  assertTrue(child.strings.isEmpty())
  return "OK"
}
