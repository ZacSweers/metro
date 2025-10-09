// https://github.com/ZacSweers/metro/issues/1144

@DependencyGraph(bindingContainers = [Foo::class])
interface PushComponent {
  val value: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes bar: Bar): PushComponent
  }
}

@BindingContainer
interface Foo {
  companion object {
    @Provides fun value(bar: Bar): String = bar.a.toString()
  }
}

interface Bar {
  val a: Int
}

fun box(): String {
  val bar =
    object : Bar {
      override val a: Int = 3
    }
  val graph = createGraphFactory<PushComponent.Factory>().create(bar)
  assertEquals(bar.a.toString(), graph.value)
  return "OK"
}
