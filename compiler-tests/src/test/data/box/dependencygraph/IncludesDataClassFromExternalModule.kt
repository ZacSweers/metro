// MODULE: lib
data class ExternalDependencies(
  val int: Int,
)

class NonDataWithCopyIsIncluded {
  fun copy(): String = "Hello" // for example, something that returns copy wording
}

class ClassWithDestructuring {
  val regularLong: Long = 3L
  operator fun component0(): Long = 4L
}

// MODULE: main(lib)
@DependencyGraph
interface AppGraph {
  val int: Int
  val copyString: String
  val regularLong: Long

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Includes dependencies: ExternalDependencies,
      @Includes nonDataWithCopyIsIncluded: NonDataWithCopyIsIncluded,
      @Includes classWithDestructuring: ClassWithDestructuring,
    ): AppGraph
  }
}

fun box(): String {
  val appGraph = createGraphFactory<AppGraph.Factory>().create(
    ExternalDependencies(3),
    NonDataWithCopyIsIncluded(),
    ClassWithDestructuring(),
  )
  assertEquals(3, appGraph.int)
  assertEquals("Hello", appGraph.copyString)
  assertEquals(3L, appGraph.regularLong)
  return "OK"
}
