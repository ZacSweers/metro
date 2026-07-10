// RUN_PIPELINE_TILL: FIR2IR
// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph(AppScope::class)
interface ParentGraph {
    val childGraphFactory: ChildGraph.Factory
}

@GraphExtension(AppScope::class)
interface ChildGraph {
    @DependencyGraph.Factory
    interface Factory {
        fun create(): ChildGraph
    }
}
