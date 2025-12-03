// https://github.com/ZacSweers/metro/issues/1462
// GENERATE_CONTRIBUTION_HINTS: false

// Single-level alias: Foo -> FooImpl
interface Foo

@Inject
@ContributesBinding(AppScope::class)
class FooImpl : Foo

@Inject
class BarA(val foo: Foo)

@Inject
class BarB(val foo: Foo)

// Multi-level alias chain: Baz -> BazMiddle -> BazImpl
interface Baz

interface BazMiddle : Baz

@Inject
@ContributesBinding(AppScope::class)
class BazImpl : BazMiddle

@Inject
class QuxA(val baz: Baz)

@Inject
class QuxB(val baz: Baz)

@Inject
@SingleIn(AppScope::class)
class Main(val barA: BarA, val barB: BarB, val quxA: QuxA, val quxB: QuxB)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val main: Main

  @Binds fun bindBaz(impl: BazMiddle): Baz
}