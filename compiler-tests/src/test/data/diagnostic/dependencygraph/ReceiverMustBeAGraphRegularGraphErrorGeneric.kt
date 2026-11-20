// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface Base<T>
@ContributesTo(AppScope::class)
interface ContributedInterface : Base<String>
@DependencyGraph(AppScope::class)
interface AppGraph

fun example(appGraph: AppGraph) {
  appGraph.asContribution<<!AS_CONTRIBUTION_ERROR!>Base<Int><!>>()
  val contributed = appGraph.asContribution<<!AS_CONTRIBUTION_ERROR!>Base<Int><!>>()
  val contributed2: Base<Int> = appGraph.<!AS_CONTRIBUTION_ERROR!>asContribution<!>()
}
