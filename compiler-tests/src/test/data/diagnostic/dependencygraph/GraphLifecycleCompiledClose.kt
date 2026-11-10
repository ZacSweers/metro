// RUN_PIPELINE_TILL: FRONTEND
// RENDER_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
@ContributesTo(AppScope::class)
interface Cleanup : CloseableGraph {
  override fun close() {}
}

// MODULE: main(lib)
// A close() compiled in another module has no FIR body, so it's checked through its modality.
@DependencyGraph(AppScope::class)
interface <!DEPENDENCY_GRAPH_ERROR!>AppGraph<!>
