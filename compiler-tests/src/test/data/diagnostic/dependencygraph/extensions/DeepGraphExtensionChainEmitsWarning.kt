// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Regression test for https://github.com/ZacSweers/metro/issues/2268. The warning fires when
// the generated chain reaches 10 nested extensions deep, where filesystem basename limits
// (255 bytes) start to become a concern for the generated nested .class files.

@GraphExtension
interface <!METRO_WARNING!>Lvl10GraphExtension<!>

@GraphExtension
interface Lvl9GraphExtension {
  val next: Lvl10GraphExtension
}

@GraphExtension
interface Lvl8GraphExtension {
  val next: Lvl9GraphExtension
}

@GraphExtension
interface Lvl7GraphExtension {
  val next: Lvl8GraphExtension
}

@GraphExtension
interface Lvl6GraphExtension {
  val next: Lvl7GraphExtension
}

@GraphExtension
interface Lvl5GraphExtension {
  val next: Lvl6GraphExtension
}

@GraphExtension
interface Lvl4GraphExtension {
  val next: Lvl5GraphExtension
}

@GraphExtension
interface Lvl3GraphExtension {
  val next: Lvl4GraphExtension
}

@GraphExtension
interface Lvl2GraphExtension {
  val next: Lvl3GraphExtension
}

@GraphExtension
interface Lvl1GraphExtension {
  val next: Lvl2GraphExtension
}

@DependencyGraph
interface RootGraph {
  val lvl1: Lvl1GraphExtension
}
