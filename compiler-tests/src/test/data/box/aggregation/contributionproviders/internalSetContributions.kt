// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// MODULE: common
interface Plugin {
  val value: String
}

// MODULE: low(common)
@Inject
@ContributesIntoSet(AppScope::class)
internal class FirstPlugin : Plugin {
  override val value = "first"
}

// MODULE: high(common)
@Inject
@ContributesIntoSet(AppScope::class)
internal class SecondPlugin : Plugin {
  override val value = "second"
}

// MODULE: main(common, low, high)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val plugins: Set<Plugin>
}

fun box(): String {
  assertEquals(
    setOf("first", "second"),
    createGraph<AppGraph>().plugins.map { it.value }.toSet(),
  )
  return "OK"
}
