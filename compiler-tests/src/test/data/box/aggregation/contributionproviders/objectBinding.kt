// Verify that object classes (singleton) work with contribution providers.
// The provides function should use irGetObject instead of calling a constructor.

interface Service {
  fun name(): String
}

@ContributesBinding(AppScope::class)
object ServiceImpl : Service {
  override fun name(): String = "ServiceImpl"
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("ServiceImpl", graph.service.name())
  return "OK"
}
