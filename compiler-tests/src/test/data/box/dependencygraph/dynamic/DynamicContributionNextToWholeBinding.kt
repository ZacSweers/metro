// The real graph binds the whole set. A dynamic @IntoSet contribution next to it isn't reported, and
// the whole set still wins.
@DependencyGraph
interface AppGraph {
  val strings: Set<String>

  @Provides fun provideStrings(): Set<String> = setOf("whole")
}

@BindingContainer
object FakeStrings {
  @Provides @IntoSet fun provideString(): String = "fake"
}

fun box(): String {
  assertEquals(setOf("whole"), createDynamicGraph<AppGraph>(FakeStrings).strings)
  return "OK"
}
