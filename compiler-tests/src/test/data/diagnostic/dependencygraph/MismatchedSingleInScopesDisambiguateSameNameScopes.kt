// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// https://github.com/ZacSweers/metro/pull/1565
package test.graph

// Uses metro's AppScope
@SingleIn(dev.zacsweers.metro.AppScope::class)
@Inject
class MyClass

// The alphabetically earlier dependent leads to the longer root path.
@Inject
class ALongRoot(val next: BLongConsumer)

@Inject
class BLongConsumer(val value: MyClass)

@Inject
class ZShortRoot(val value: MyClass)

// Graph uses a custom AppScope
abstract class AppScope private constructor()

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface <!INCOMPATIBLE_SCOPE!>ExampleGraph<!> {
  val longPath: ALongRoot

  // Keep the first accessor when scalar and provider roots request the same binding.
  val shortPath: ZShortRoot
  val shortProvider: () -> ZShortRoot
}
