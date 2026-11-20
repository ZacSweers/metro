// METRO_JVM_ONLY

import kotlin.jvm.JvmSuppressWildcards

@DependencyGraph
interface ExampleGraph {
  @Multibinds(allowEmpty = true)
  val ints: Set<Int>

  val exampleClass: ExampleClass
}

@Inject
class ExampleClass(ints: Set<@JvmSuppressWildcards Int>)

fun box(): String {
  assertNotNull(createGraph<ExampleGraph>().exampleClass)
  return "OK"
}
