// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// https://github.com/ZacSweers/metro/issues/887

// MODULE: lib1
interface Foo

// MODULE: lib2(lib1)
@ContributesBinding(AppScope::class)
@Inject
internal class FooImpl : Foo

// MODULE: main(lib1 lib2)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val <!METRO_ERROR!>foo<!>: Foo
}
