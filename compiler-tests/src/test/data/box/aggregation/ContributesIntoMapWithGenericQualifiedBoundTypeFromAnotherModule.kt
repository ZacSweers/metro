// MODULE: lib
// FILE: Library.kt
package test

interface ContributedInterface<T>

@ContributesIntoMap(
  AppScope::class,
  binding<@ClassKey @Named("named") ContributedInterface<String>>()
)
@Inject
class Impl : ContributedInterface<String>

// MODULE: main(lib)
// FILE: Main.kt
package test

import kotlin.reflect.KClass

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("named") val contributedInterfaces: Map<KClass<*>, ContributedInterface<String>>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces = graph.contributedInterfaces
  assertNotNull(contributedInterfaces)
  assertEquals(1, contributedInterfaces.size)
  assertEquals(Impl::class, contributedInterfaces.entries.first().key)
  assertEquals(Impl::class, contributedInterfaces.entries.first().value::class)
  return "OK"
}
