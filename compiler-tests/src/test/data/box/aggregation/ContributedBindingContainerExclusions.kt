// Similar to the BindingContainerViaAnnotation test but contributed
@DependencyGraph(AppScope::class, excludes = [IntBinding1::class])
interface AppGraph {
  val int: Int
  val unitGraph: UnitGraph
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

// Test graph extension excludes
@GraphExtension(Unit::class, excludes = [StringBinding1::class])
interface UnitGraph {
  val string: String
}

@ContributesTo(Unit::class)
@BindingContainer
object StringBinding1 {
  @Provides fun provideString(): String = "binding1"
}

@ContributesTo(Unit::class)
@BindingContainer
object StringBinding2 {
  @Provides fun provideString(): String = "binding2"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(2, graph.int)
  assertEquals("binding2", graph.unitGraph.string)
  return "OK"
}
