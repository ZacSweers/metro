// Stress test: @GraphPrivate binding exposed via @Binds alias and multibinding across multiple
// levels of graph inheritance.
// - Parent: private String, public Binds CharSequence, IntoSet CharSequence
// - Child: no new bindings, just passes through
// - Grandchild: accesses CharSequence, Set<CharSequence>, and a derived Int

@Scope annotation class ChildScope

@Scope annotation class GrandchildScope

@DependencyGraph(AppScope::class)
interface ParentGraph {
  @GraphPrivate @SingleIn(AppScope::class) @Provides fun provideString(): String = "hello"

  @Binds fun bindCharSequence(value: String): CharSequence

  @Provides @IntoSet fun charSequenceIntoSet(value: CharSequence): CharSequence = value

  fun createChild(): ChildGraph
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  fun createGrandchild(): GrandchildGraph
}

@GraphExtension(GrandchildScope::class)
interface GrandchildGraph {
  val text: CharSequence
  val texts: Set<CharSequence>
  val length: Int

  @Provides fun provideLength(value: CharSequence): Int = value.length
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.createChild()
  val grandchild = child.createGrandchild()

  assertEquals("hello", grandchild.text)
  assertEquals(setOf("hello"), grandchild.texts)
  assertEquals(5, grandchild.length)

  return "OK"
}
