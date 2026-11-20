// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

abstract class Parent

@GraphExtension(Parent::class)
interface ParentGraph {
  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun create(
        @Provides @ForScope(Parent::class) string: String
    ): ParentGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
