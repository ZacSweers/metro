// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

sealed interface LoggedInScope

@Inject @SingleIn(AppScope::class) class Dependency

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val dependency: Dependency
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
    val dependency: Dependency

    @GraphExtension.Factory @ContributesTo(AppScope::class)
    interface Factory {
        fun createLoggedInGraph(): LoggedInGraph
    }
}
