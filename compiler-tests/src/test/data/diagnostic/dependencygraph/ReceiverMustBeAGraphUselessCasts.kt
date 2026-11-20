// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@ContributesTo(AppScope::class)
interface ContributedInterface
@DependencyGraph(AppScope::class)
interface AppGraph

fun example(appGraph: AppGraph) {
  appGraph.asContribution<<!AS_CONTRIBUTION_ERROR!>AppGraph<!>>()
  val contributed = appGraph.asContribution<<!AS_CONTRIBUTION_ERROR!>AppGraph<!>>()
  val contributed2: AppGraph = appGraph.<!AS_CONTRIBUTION_ERROR!>asContribution<!>()
}
