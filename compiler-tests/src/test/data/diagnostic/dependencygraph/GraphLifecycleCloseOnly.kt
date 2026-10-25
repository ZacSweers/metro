// RUN_PIPELINE_TILL: FIR2IR
// METRO_JVM_ONLY
// SHRINK_UNUSED_BINDINGS: true

class Scope
@Inject class OnlyClose
class NeverRequested(val only: OnlyClose)
@Inject class Indirect
@Inject class Accessor(val indirect: Indirect)
@SingleIn(Scope::class) @Inject class Shared
interface Included { val external: String }

@GraphExtension interface Child { val shared: Shared }

@DependencyGraph(Scope::class)
interface AppGraph : CloseableGraph {
  val accessor: Accessor
  val child: Child
  @OnGraphClose fun stop(<!CLOSE_ONLY_BINDING, UNSCOPED_LIFECYCLE_PARAMETER!>only: OnlyClose<!>) {}
  @Suppress("UNSCOPED_LIFECYCLE_PARAMETER")
  @OnGraphClose fun others(indirect: Indirect, shared: Shared, external: String) {}
  @Suppress("CLOSE_ONLY_BINDING")
  @OnGraphClose fun suppressed(<!UNSCOPED_LIFECYCLE_PARAMETER!>only: OnlyClose<!>) {}
  @Provides fun unused(only: OnlyClose): NeverRequested = NeverRequested(only)
  @DependencyGraph.Factory fun interface Factory {
    fun create(@Includes included: Included): AppGraph
  }
}
