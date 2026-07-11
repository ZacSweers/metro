// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph(AppScope::class)
interface ParentGraph {
    val childGraphFactory: ChildGraph.Factory
}

@GraphExtension(AppScope::class)
interface ChildGraph {
    <!GRAPH_CREATORS_ERROR!>@DependencyGraph.Factory<!>
    interface Factory {
        fun create(): ChildGraph
    }
}

@GraphExtension(AppScope::class)
interface OtherChildGraph {
    <!GRAPH_CREATORS_ERROR!>@DependencyGraph.Factory<!>
    interface Factory {
        fun create(): ChildGraph
    }
}
