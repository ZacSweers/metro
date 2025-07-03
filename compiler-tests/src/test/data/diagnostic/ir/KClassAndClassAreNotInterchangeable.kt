// Repro for https://github.com/ZacSweers/metro/issues/644
import kotlin.reflect.KClass

interface Foo

@MapKey annotation class FooKey(val value: KClass<out Foo>)

@DependencyGraph
interface FooGraph {
  // Uses Class but map key uses KClass
  val foos: Map<KClass<out Foo>, Foo>
}

@Inject class Bar(val foos: Map<Class<out Foo>, Foo>)