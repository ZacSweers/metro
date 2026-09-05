// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// CONTRIBUTES_AS_INJECT
// NON_PUBLIC_CONTRIBUTION_SEVERITY: ERROR

// Consumers in a separate module access the internal singleton through its contribution provider.

// MODULE: common
interface Service {
  fun name(): String
}

// MODULE: lib(common)
@ContributesBinding(AppScope::class)
internal object ServiceImpl : Service {
  override fun name(): String = "OK"
}

// MODULE: main(lib, common)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("OK", graph.service.name())
  assertSame(graph.service, createGraph<AppGraph>().service)
  return "OK"
}
