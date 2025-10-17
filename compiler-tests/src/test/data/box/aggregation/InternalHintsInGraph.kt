// https://github.com/ZacSweers/metro/issues/694
// The syntax goes name(deps)(friends)(dependsOn)

// MODULE: lib
interface ContributedInterface

@ContributesBinding(AppScope::class)
@Inject
internal class Impl : ContributedInterface

// MODULE: main()(lib)
@DependencyGraph(AppScope::class)
internal interface AppGraph {
  val contributed: ContributedInterface
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.contributed)
  return "OK"
}