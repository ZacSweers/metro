// Regression test that ensures that multiple contributes annotations only yield
// one generated hint file per scope

// FILE: file0.kt
interface Base1
interface Base2
interface Base3

// FILE: file1.kt
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<Base3>())
@ContributesIntoSet(AppScope::class, binding = binding<Base2>())
@ContributesBinding(AppScope::class, binding = binding<Base1>())
@Inject
class Base1Impl : Base1, Base2, Base3


// FILE: file2.kt
@DependencyGraph(AppScope::class)
interface ExampleGraph {
  val prewarms: Set<Base3>
  val cacheHolders: Set<Base2>
  val composerPrefs: Base1
}