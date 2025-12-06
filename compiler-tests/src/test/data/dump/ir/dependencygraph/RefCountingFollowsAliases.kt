// https://github.com/ZacSweers/metro/issues/1462
// GENERATE_CONTRIBUTION_HINTS: false

// Single-level alias: Foo -> FooImpl
interface Foo

@Inject
@ContributesBinding(AppScope::class)
class FooImpl : Foo

@Inject
class BarA(val foo: Provider<Foo>)

@Inject
class BarB(val foo: Provider<Foo>)

// Multi-level alias chain: Baz -> BazMiddle -> BazImpl
interface Baz

interface BazMiddle : Baz

@Inject
@ContributesBinding(AppScope::class)
class BazImpl : BazMiddle

@Inject
class QuxA(val baz: Provider<Baz>)

@Inject
class QuxB(val baz: Provider<Baz>)

@Inject
@SingleIn(AppScope::class)
class Main(val barA: Provider<BarA>, val barB: Provider<BarB>, val quxA: Provider<QuxA>, val quxB: Provider<QuxB>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val main: Main

  @Binds fun bindBaz(impl: BazMiddle): Baz
}