// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// https://github.com/ZacSweers/metro/pull/1785

interface A
interface B
interface C

@DependencyGraph
interface <!METRO_ERROR!>AppGraph<!> {
  val a: A

  // If the reporter tries to link A -> C directly, it will crash.

  @Provides fun provideA(b: B): A = object : A {}
  @Provides fun provideB(a: A, c: Lazy<C>): B = object : B {}
  @Provides fun provideC(b: B): C = object : C {}
}
