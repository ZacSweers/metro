// https://github.com/ZacSweers/metro/issues/1001
@DependencyGraph
interface AppGraph {
  val child: ChildGraph
  val child2: ChildGraph2
}

@GraphExtension(bindingContainers = [StringBindings::class, IntBindings::class])
interface ChildGraph {
  val string: String
}

@BindingContainer(includes = [IntBindings::class])
object StringBindings {
  @Provides fun provideString(int: Int): String = int.toString()
}

@GraphExtension(bindingContainers = [StringBindings2::class, IntBindings::class])
interface ChildGraph2 {
  val string: String
}

@BindingContainer(includes = [IntBindings::class])
object StringBindings2 {
  @Provides fun provideString(int: Int): String = int.toString()
}

@BindingContainer
object IntBindings {
  @Provides fun provideInt(): Int = 3
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertEquals("3", appGraph.child.string)
  assertEquals("3", appGraph.child2.string)
  return "OK"
}