// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt

interface Service {
  val text: String
}

@ContributesBinding(AppScope::class)
@Inject
internal class ServiceImpl(override val text: String) : Service

// MODULE: main(lib)
// LANGUAGE: -CompanionBlocks
// COMPANION_MODE: COMPANION_OBJECT
// FILE: Main.kt

// The consumer stubs invisible helpers on the producer's helper owner.
@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service

  @Provides fun text(): String = "invisible"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("invisible", graph.service.text)
  return "OK"
}
