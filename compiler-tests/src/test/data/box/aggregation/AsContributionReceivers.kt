interface Base<T>

@ContributesTo(AppScope::class)
interface ContributedInterface : Base<String>

@DependencyGraph(AppScope::class)
interface AppGraph

private fun direct(graph: AppGraph) {
  graph.asContribution<ContributedInterface>()
  val explicit = graph.asContribution<ContributedInterface>()
  val inferred: ContributedInterface = graph.asContribution()
  assertSame(explicit, inferred)
}

private fun generic(graph: AppGraph) {
  graph.asContribution<Base<String>>()
  val explicit = graph.asContribution<Base<String>>()
  val inferred: Base<String> = graph.asContribution()
  assertSame(explicit, inferred)
}

interface IndirectBase

@ContributesTo(Unit::class)
interface IndirectContribution : IndirectBase

@DependencyGraph(Unit::class)
interface IndirectGraph

private fun indirect(graph: IndirectGraph) {
  graph.asContribution<IndirectBase>()
  val explicit = graph.asContribution<IndirectBase>()
  val inferred: IndirectBase = graph.asContribution()
  assertSame(explicit, inferred)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  direct(graph)
  generic(graph)
  indirect(createGraph<IndirectGraph>())
  return "OK"
}
