// MODULE: lib
interface Base {
  val text: String

  interface Factory {
    fun create(text: String): Base
  }
}

// MODULE: impl(lib)
@AssistedInject
class BaseImpl(
  @Assisted override val text: String
) : Base {

  @ContributesBinding(AppScope::class)
  @AssistedFactory
  interface Factory : Base.Factory {
    override fun create(text: String): BaseImpl
  }
}

// MODULE: main(lib, impl)
@Inject
class Example(
  factory: Base.Factory
) {
  val text = factory.create("example").text
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val example: Example
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("example", graph.example.text)
  return "OK"
}
