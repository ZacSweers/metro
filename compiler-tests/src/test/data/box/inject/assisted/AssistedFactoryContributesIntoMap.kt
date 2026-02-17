// Verify that an @AssistedFactory can use @ContributesIntoMap to contribute
// itself to a multibound map. This was enabled by allowing contribution annotations
// on assisted factories in PR 883.

import kotlin.reflect.KClass

@MapKey
annotation class FooKey(val value: KClass<*>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val factories: Map<KClass<*>, Foo.Factory>
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
  @ContributesIntoMap(AppScope::class)
  @FooKey(FooImpl::class)
  @AssistedFactory
  interface Factory : Foo.Factory {
    override fun create(text: String): FooImpl
  }
}

fun box(): String {
  val factories = createGraph<AppGraph>().factories
  val factory = factories[FooImpl::class] ?: return "Fail: factory not found"
  val foo = factory.create("foo")
  assertEquals("foo", foo.text)
  return "OK"
}
