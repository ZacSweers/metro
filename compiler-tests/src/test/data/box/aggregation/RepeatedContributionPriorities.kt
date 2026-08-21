interface FirstService
interface SecondService

@Inject
@ContributesBinding(AppScope::class, binding = binding<FirstService>(), priority = 1)
@ContributesBinding(AppScope::class, binding = binding<SecondService>(), priority = 20)
class FirstImplementation : FirstService, SecondService

@Inject
@ContributesBinding(AppScope::class, binding = binding<FirstService>(), priority = 10)
@ContributesBinding(AppScope::class, binding = binding<SecondService>(), priority = 5)
class SecondImplementation : FirstService, SecondService

@DependencyGraph(AppScope::class)
interface AppGraph {
  val firstService: FirstService
  val secondService: SecondService
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertIs<SecondImplementation>(graph.firstService)
  assertIs<FirstImplementation>(graph.secondService)
  return "OK"
}
