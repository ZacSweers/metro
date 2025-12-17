// MODULE: scopes
abstract class LoggedInScope private constructor()

// MODULE: feature
interface SomeRepository

// MODULE: feature-impl(feature, scopes)
@ContributesBinding(LoggedInScope::class)
@Inject
internal class SomeRepositoryImpl : SomeRepository

// MODULE: graphs(scopes, feature)
@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val someRepository: SomeRepository

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun create(): LoggedInGraph
  }
}

// MODULE: main(graphs, feature, feature-impl, scopes)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val loggedInGraphFactory: LoggedInGraph.Factory
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  graph.loggedInGraphFactory.create().someRepository as SomeRepository
  return "OK"
}
