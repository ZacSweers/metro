// MODULE: lib
@HasMemberInjections
open class Parent {
  val values = mutableListOf<Any>()

  @Inject fun accept(value: Int) {
    values += value
  }

  @Inject fun accept(value: String) {
    values += value
  }
}

// MODULE: main(lib)
class Child : Parent() {
  @Inject fun accept(value: Boolean) {
    values += value
  }

  @Inject fun accept(value: Long) {
    values += value
  }
}

@DependencyGraph
interface Graph {
  fun inject(child: Child)

  @Provides fun number(): Int = 1
  @Provides fun text(): String = "two"
  @Provides fun flag(): Boolean = true
  @Provides fun count(): Long = 4L
}

fun box(): String {
  val child = Child()
  createGraph<Graph>().inject(child)
  assertEquals(listOf<Any>(1, "two", true, 4L), child.values.toList())
  return "OK"
}
