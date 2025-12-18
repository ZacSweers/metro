// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// https://github.com/ZacSweers/metro/pull/1565
package test.graph

// Uses metro's AppScope
@SingleIn(dev.zacsweers.metro.AppScope::class)
@Inject
class MyClass

// Graph uses a custom AppScope
abstract class AppScope private constructor()

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface <!METRO_ERROR!>ExampleGraph<!> {
  val myClass: MyClass
}
