@DependencyGraph(AppScope::class)
interface ExampleGraph {
  val exampleClass: ExampleClass
}

@SingleIn(AppScope::class)
@Inject
class ExampleClass

fun box(): String {
  assertNotNull(createGraph<ExampleGraph>().exampleClass)
  return "OK"
}
