import kotlin.reflect.KClass

@MapKey
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FooKey(val value: KClass<out Foo>)

interface Foo {
  fun bar(): String
}

@Inject
@ContributesIntoMap(AppScope::class)
@FooKey(FooBoundDirectly::class)
class FooBoundDirectly : Foo {
  override fun bar() = "abc"
}

object FooFromBindingContainer : Foo {
  override fun bar() = "xyz"
}

@BindingContainer
@ContributesTo(AppScope::class)
object FooBindingContainer {
  @Provides
  @IntoMap
  @FooKey(FooFromBindingContainer::class)
  fun foo(): Foo = FooFromBindingContainer
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val foos: Map<KClass<out Foo>, Foo>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val foos = graph.foos
  assertEquals(expected = 2, actual = foos.size)
  assertIs<FooBoundDirectly>(foos[FooBoundDirectly::class])
  assertIs<FooFromBindingContainer>(foos[FooFromBindingContainer::class])
  return "OK"
}
