// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@ContributesTo(AppScope::class)
interface ContributedInterface
interface AppGraph

fun example(appGraph: AppGraph) {
  <!AS_CONTRIBUTION_ERROR!>appGraph<!>.asContribution<ContributedInterface>()
  val contributed = <!AS_CONTRIBUTION_ERROR!>appGraph<!>.asContribution<ContributedInterface>()
  val contributed2: ContributedInterface = <!AS_CONTRIBUTION_ERROR!>appGraph<!>.asContribution()
}
