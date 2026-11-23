// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

interface AliasPlugin
interface ProviderPlugin

@Inject
@ContributesIntoSet(AppScope::class)
class GeneratedProviderPlugin : AliasPlugin

@Inject
@ExposeImplBinding
@ContributesIntoSet(AppScope::class)
class ExposedAliasPlugin : AliasPlugin

@Inject
@ExposeImplBinding
@ContributesIntoSet(AppScope::class)
class ExposedProviderPlugin : ProviderPlugin

@Inject
@ContributesIntoSet(AppScope::class)
class HiddenProviderPlugin : ProviderPlugin

@DependencyGraph(AppScope::class)
interface AppGraph {
  val aliasPlugins: Set<AliasPlugin>
  val providerPlugins: Set<ProviderPlugin>
  val retainedAliasImplementation: ExposedProviderPlugin
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(
    setOf("GeneratedProviderPlugin", "ExposedAliasPlugin"),
    graph.aliasPlugins.map { it::class.simpleName }.toSet(),
  )
  // The generated provider sorts before the alias by callable ID, even though its class sorts last.
  assertEquals(
    listOf("HiddenProviderPlugin", "ExposedProviderPlugin"),
    graph.providerPlugins.map { it::class.simpleName },
  )
  assertIs<ExposedProviderPlugin>(graph.retainedAliasImplementation)
  return "OK"
}
