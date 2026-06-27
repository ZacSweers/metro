@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val validFoo: ValidFoo
}

@BindingContainer
interface Bindings {
  @Binds fun bindValidFoo(): ValidFoo
}

@Inject
class ValidFoo {
  val value: String = "constructor"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("constructor", graph.validFoo.value)
  return "OK"
}
