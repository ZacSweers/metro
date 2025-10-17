// https://github.com/ZacSweers/metro/issues/733
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val text: String
}

@ContributesTo(AppScope::class)
@BindingContainer
object SampleDependency {
  @Provides fun provideText(): String = "Hello, Metro!"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello, Metro!", graph.text)
  return "OK"
}
