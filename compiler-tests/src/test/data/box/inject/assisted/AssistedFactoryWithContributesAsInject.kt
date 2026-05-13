// CONTRIBUTES_AS_INJECT
@DependencyGraph(AppScope::class)
interface AppGraph {
  val factory: Foo.Factory
}

interface Foo {
  val text: String

  interface Factory {
    fun create(text: String): Foo
  }
}

@AssistedInject
class FooImpl(
  @Assisted override val text: String,
) : Foo {
  @ContributesBinding(AppScope::class)
  @AssistedFactory
  interface Factory : Foo.Factory {
    override fun create(text: String): FooImpl
  }
}

fun box(): String {
  val foo = createGraph<AppGraph>().factory.create("foo")
  assertEquals("foo", foo.text)
  return "OK"
}