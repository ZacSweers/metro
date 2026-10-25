@GraphExtension
interface Child {
  @GraphExtension.Factory fun interface Factory { fun create(): Child }
}

@GraphExtension interface DirectChild

@DependencyGraph
interface Parent : CloseableGraph, Child.Factory {
  val factory: Child.Factory
  val child: DirectChild
}

fun box(): String {
  val parent = createGraph<Parent>()
  val captured = parent.factory
  captured.create()
  parent.close()
  check(runCatching { captured.create() }.exceptionOrNull() is IllegalStateException)
  check(runCatching { parent.create() }.exceptionOrNull() is IllegalStateException)
  check(runCatching { parent.child }.exceptionOrNull() is IllegalStateException)
  return "OK"
}
