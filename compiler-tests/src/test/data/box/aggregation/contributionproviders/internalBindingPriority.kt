// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// MODULE: common
interface Service {
  val value: String
}

// MODULE: low(common)
@Inject
@ContributesBinding(AppScope::class, priority = -10)
internal class LowPriorityService : Service {
  override val value: String = "low"
}

// MODULE: high(common)
@Inject
@ContributesBinding(AppScope::class, priority = 10)
internal class HighPriorityService : Service {
  override val value: String = "high"
}

// MODULE: main(common, low, high)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  assertEquals("high", createGraph<AppGraph>().service.value)
  return "OK"
}
