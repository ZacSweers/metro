// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// MODULE: common
interface Handler {
  val value: String
}

// MODULE: low(common)
@Inject
@StringKey("shared")
@ContributesIntoMap(AppScope::class, priority = -10)
internal class LowPriorityHandler : Handler {
  override val value: String = "low"
}

// MODULE: high(common)
@Inject
@StringKey("shared")
@ContributesIntoMap(AppScope::class, priority = 10)
internal class HighPriorityHandler : Handler {
  override val value: String = "high"
}

@Inject
@StringKey("other")
@ContributesIntoMap(AppScope::class)
internal class OtherHandler : Handler {
  override val value: String = "other"
}

// MODULE: main(common, low, high)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val handlers: Map<String, Handler>
}

fun box(): String {
  val handlers = createGraph<AppGraph>().handlers
  assertEquals(setOf("shared", "other"), handlers.keys)
  assertEquals("high", handlers.getValue("shared").value)
  assertEquals("other", handlers.getValue("other").value)
  return "OK"
}
