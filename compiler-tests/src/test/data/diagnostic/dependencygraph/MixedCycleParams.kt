// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Regression test for https://github.com/ZacSweers/metro/pull/1121#issuecomment-3374140082

interface A {
  val isReal: Boolean
}

@Inject class B(a: A)

@Inject
class RealA(b: Lazy<B>) : A {
  override val isReal: Boolean get() = true
}

@ContributesBinding(Unit::class)
@Inject
class FakeA(b: B, realA: RealA) : A {
  override val isReal: Boolean get() = false
}

@DependencyGraph(Unit::class)
interface <!METRO_ERROR!>CycleGraph<!> {
  val a: A
}
