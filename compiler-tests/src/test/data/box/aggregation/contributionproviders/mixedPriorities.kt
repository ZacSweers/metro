// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

interface Service
interface OtherService
interface Handler

@Inject
@ContributesBinding(AppScope::class, priority = 1)
class LowerProviderService : Service

@Inject
@ExposeImplBinding
@ContributesBinding(AppScope::class, priority = 100)
class HigherAliasService : Service

@Inject
@ExposeImplBinding
@ContributesBinding(AppScope::class, priority = 1)
class LowerAliasOtherService : OtherService

@Inject
@ContributesBinding(AppScope::class, priority = 100)
class HigherProviderOtherService : OtherService

@Inject
@StringKey("alias")
@ContributesIntoMap(AppScope::class, priority = 1)
class LowerProviderHandler : Handler

@Inject
@ExposeImplBinding
@StringKey("alias")
@ContributesIntoMap(AppScope::class, priority = 100)
class HigherAliasHandler : Handler

@Inject
@ExposeImplBinding
@StringKey("provider")
@ContributesIntoMap(AppScope::class, priority = 1)
class LowerAliasHandler : Handler

@Inject
@StringKey("provider")
@ContributesIntoMap(AppScope::class, priority = 100)
class HigherProviderHandler : Handler

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
  val otherService: OtherService
  val handlers: Map<String, Handler>
  val retainedAliasImplementation: LowerAliasOtherService
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertIs<HigherAliasService>(graph.service)
  assertIs<HigherProviderOtherService>(graph.otherService)
  assertIs<HigherAliasHandler>(graph.handlers.getValue("alias"))
  assertIs<HigherProviderHandler>(graph.handlers.getValue("provider"))
  assertIs<LowerAliasOtherService>(graph.retainedAliasImplementation)
  return "OK"
}
