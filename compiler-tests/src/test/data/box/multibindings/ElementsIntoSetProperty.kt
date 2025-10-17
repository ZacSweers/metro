// Testing ElementsIntoSet annotation on properties
@DependencyGraph(AppScope::class)
interface AppGraph {
  val strings: Set<String>

  @Provides
  @ElementsIntoSet
  val emptyStrings: Collection<String>
    get() = emptyList()

  @Provides
  val annotationOnAccessor: Collection<String>
    @ElementsIntoSet
    get() = listOf("c")

  @Provides
  @get:ElementsIntoSet
  val annotationOnSiteTarget: Collection<String>
    get() = listOf("d")

  @Provides
  @ElementsIntoSet
  val moreStrings: Collection<String>
    get() = listOf("a", "b")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf("a", "b", "c", "d"), graph.strings)
  return "OK"
}