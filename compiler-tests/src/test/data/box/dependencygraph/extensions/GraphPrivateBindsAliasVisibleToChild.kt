// @Binds public alias of @GraphPrivate binding is visible to child graphs
@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph {
  @SingleIn(AppScope::class) @GraphPrivate @Provides fun provideString(): String = "hello"
  @Binds fun bind(value: String): CharSequence

  fun createChild(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val text: CharSequence
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.createChild()
  assertEquals("hello", child.text)
  return "OK"
}
