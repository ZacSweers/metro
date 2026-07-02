// MERGED_SUPERTYPE_CHUNK_SIZE: 2

interface ServiceA {
  val a: String
}

interface ServiceB {
  val b: String
}

interface ServiceC {
  val c: String
}

@ContributesBinding(AppScope::class)
@Inject
class ServiceAImpl : ServiceA {
  override val a: String = "A"
}

@ContributesBinding(AppScope::class)
@Inject
class ServiceBImpl : ServiceB {
  override val b: String = "B"
}

@ContributesBinding(AppScope::class)
@Inject
class ServiceCImpl : ServiceC {
  override val c: String = "C"
}

@MergeContributionsInIr
@DependencyGraph(scope = AppScope::class)
interface AppGraph {
  val a: ServiceA
  val b: ServiceB
  val c: ServiceC
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("A", graph.a.a)
  assertEquals("B", graph.b.b)
  assertEquals("C", graph.c.c)
  return "OK"
}
