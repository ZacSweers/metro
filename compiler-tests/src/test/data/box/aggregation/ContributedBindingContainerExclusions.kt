// Similar to the BindingContainerViaAnnotation test but contributed
@DependencyGraph(AppScope::class, excludes = [IntBinding1::class])
interface AppGraph {
  val int: Int
}

@ContributesTo(AppScope::class)
@BindingContainer
object IntBinding1 {
  @Provides fun provideInt(): Int = 1
}

@ContributesTo(AppScope::class)
@BindingContainer
object IntBinding2 {
  @Provides fun provideInt(): Int = 2
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(2, graph.int)
  return "OK"
}
