// MIN_COMPILER_VERSION: 2.5.0-Beta1
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

interface AssistedService {
  val text: String

  interface Factory {
    fun create(id: Int): AssistedService
  }
}

@AssistedInject
internal class AssistedServiceImpl(@Assisted id: Int, value: String) : AssistedService {
  override val text: String = "$value-$id"

  @ContributesBinding(AppScope::class)
  @AssistedFactory
  interface Factory : AssistedService.Factory {
    override fun create(id: Int): AssistedServiceImpl
  }
}

// MODULE: main(lib)
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Main.kt

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
  val assistedFactory: AssistedService.Factory

  @Provides fun text(): String = "invisible"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("invisible", graph.service.text)
  assertEquals("invisible-7", graph.assistedFactory.create(7).text)
  return "OK"
}
