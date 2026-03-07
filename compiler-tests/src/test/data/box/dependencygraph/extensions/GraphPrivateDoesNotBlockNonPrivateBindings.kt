// @GraphPrivate binding is not exposed but non-private scoped binding is still visible to child
@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph {
  @SingleIn(AppScope::class) @GraphPrivate @Provides fun provideString(): String = "hello"
  @SingleIn(AppScope::class) @Provides fun provideInt(): Int = 42

  fun createChild(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val number: Int
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.createChild()
  assertEquals(42, child.number)
  return "OK"
}
