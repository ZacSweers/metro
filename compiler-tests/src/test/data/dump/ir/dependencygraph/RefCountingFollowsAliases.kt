// https://github.com/ZacSweers/metro/issues/1462
// GENERATE_CONTRIBUTION_HINTS: false
interface Foo

@Inject
@ContributesBinding(AppScope::class)
class FooImpl : Foo

@Inject
class BarA(val foo: Foo)

@Inject
class BarB(val foo: Foo)

@Inject
@SingleIn(AppScope::class)
class Main(val barA: BarA, val barB: BarB)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val main: Main
}