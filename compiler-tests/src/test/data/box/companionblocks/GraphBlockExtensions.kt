// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPANION_BLOCK
@GraphExtension interface ChildGraph { val value: Int }
@DependencyGraph
interface AppGraph {
  companion { @Provides fun value(): Int = 3 }
  fun child(): ChildGraph
}
fun box(): String {
  assertEquals(3, AppGraph().child().value)
  return "OK"
}
