// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

sealed interface GrandParentScope
sealed interface ParentScope
sealed interface ChildScope

@ContributesTo(GrandParentScope::class)
interface TestContribution {
    @Provides
    fun provideString(): String = ""
}

@SingleIn(GrandParentScope::class)
@DependencyGraph(scope = GrandParentScope::class)
interface GrandParentGraph

@GraphExtension(ParentScope::class)
interface ParentGraph {
    @GraphExtension.Factory @ContributesTo(GrandParentScope::class)
    interface Factory {
        fun createParentGraph(): ParentGraph
    }
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
    val string: String

    @GraphExtension.Factory @ContributesTo(ParentScope::class)
    interface Factory {
        fun createChildGraph(): ChildGraph
    }
}
