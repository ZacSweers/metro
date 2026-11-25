// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// MODULE: lib
abstract class OtherScope

interface AppService
interface OtherService

@Inject
@ContributesBinding(AppScope::class, binding = binding<AppService>())
@ContributesBinding(OtherScope::class, binding = binding<OtherService>())
internal class Services : AppService, OtherService

// MODULE: main(lib)
object Fallback : AppService, OtherService

@Inject
class Consumer(val app: AppService = Fallback, val other: OtherService = Fallback)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val consumer: Consumer
}

@DependencyGraph(OtherScope::class)
interface OtherGraph {
  val consumer: Consumer
}

fun box(): String {
  // Each graph should see only its own binding from the library.
  val app = createGraph<AppGraph>().consumer
  assertNotSame<AppService>(Fallback, app.app)
  assertSame<OtherService>(Fallback, app.other)

  val other = createGraph<OtherGraph>().consumer
  assertSame<AppService>(Fallback, other.app)
  assertNotSame<OtherService>(Fallback, other.other)
  return "OK"
}
