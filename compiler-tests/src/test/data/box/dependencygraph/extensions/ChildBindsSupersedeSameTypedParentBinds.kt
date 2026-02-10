// A child graph extension's @Binds should supersede a parent's @Binds of the
// same type. This tests the binds-specific path of the parent binding
// superseding logic.
// https://github.com/ZacSweers/metro/pull/883

interface Service

@Inject class ParentImpl : Service
@Inject class ChildImpl : Service

@DependencyGraph
interface ParentGraph {
  @Binds val ParentImpl.bind: Service

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val service: Service

  @Binds val ChildImpl.bind: Service

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.childGraphFactory().create()
  assertTrue(child.service is ChildImpl)
  return "OK"
}
