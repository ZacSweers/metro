// Regression test for https://github.com/ZacSweers/metro/issues/2268. Verifies that nested
// @GraphExtension impls fall back to a stable hashed simple name once the projected nested
// .class basename would exceed the 255-byte per-segment filesystem limit. The long extension
// interface names below are deliberately verbose so the threshold is crossed within a few
// levels of nesting and the dump stays short.

@GraphExtension
interface Lvl3ExtremelyLongGraphExtensionInterfaceNameUsedToTriggerNameHashing

@GraphExtension
interface Lvl2ExtremelyLongGraphExtensionInterfaceNameUsedToTriggerNameHashing {
  val next: Lvl3ExtremelyLongGraphExtensionInterfaceNameUsedToTriggerNameHashing
}

@GraphExtension
interface Lvl1ExtremelyLongGraphExtensionInterfaceNameUsedToTriggerNameHashing {
  val next: Lvl2ExtremelyLongGraphExtensionInterfaceNameUsedToTriggerNameHashing
}

@DependencyGraph
interface RootGraph {
  val lvl1: Lvl1ExtremelyLongGraphExtensionInterfaceNameUsedToTriggerNameHashing
}
