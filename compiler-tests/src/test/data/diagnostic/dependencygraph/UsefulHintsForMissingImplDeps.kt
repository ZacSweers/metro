// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: core
interface Base

// MODULE: lib1(core)
@Inject
class FooImpl : Base

// MODULE: lib2(lib1 core)
@ContributesTo(AppScope::class)
@BindingContainer
interface Bindings {
  @Binds val FooImpl.bind: Base
}

// MODULE: main(lib2 core)
@DependencyGraph(AppScope::class)
interface <!METRO_ERROR!>AppGraph<!> {
  val base: Base
}
