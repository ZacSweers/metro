// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

interface ContributedInterface

@AssistedInject
class Impl(@Assisted input: String) {
  @ContributesBinding(AppScope::class)
  @AssistedFactory
  fun interface Factory : ContributedInterface {
    fun create(input: String): Impl
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}
