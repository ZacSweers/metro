// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// https://github.com/ZacSweers/metro/issues/694
// The syntax goes name(deps)(friends)(dependsOn)

// MODULE: lib
interface ContributedInterface

@ContributesBinding(AppScope::class)
@Inject
internal class Impl : ContributedInterface

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val <!METRO_ERROR!>contributed<!>: ContributedInterface
}
