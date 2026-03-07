// When a binding container is excluded, its replaces annotation should have no effect.
@DependencyGraph(AppScope::class, excludes = [IntBinding2::class])
interface AppGraph {
  val int: Int
}

@ContributesTo(AppScope::class)
@BindingContainer
object IntBinding1 {
  @Provides fun provideInt(): Int = 1
}

@ContributesTo(AppScope::class, replaces = [IntBinding1::class])
@BindingContainer
object IntBinding2 {
  @Provides fun provideInt(): Int = 2
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  // IntBinding2 is excluded, so its replaces=[IntBinding1] should have no effect
  assertEquals(1, graph.int)
  return "OK"
}
