// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

abstract class OtherScope

interface AppService
interface OtherService
object Fallback : OtherService

@Inject
@ContributesBinding(AppScope::class, binding = binding<AppService>())
@ContributesBinding(OtherScope::class, binding = binding<OtherService>())
class Services : AppService, OtherService

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: AppService
  val usedDefault: Boolean

  // OtherService is only contributed to OtherScope.
  @Provides fun checkDefault(other: OtherService = Fallback): Boolean = other === Fallback
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertIs<Services>(graph.service)
  assertTrue(graph.usedDefault)
  return "OK"
}
