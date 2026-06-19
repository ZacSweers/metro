// https://github.com/ZacSweers/metro/pull/2458
class Foo

@DependencyGraph(AppScope::class)
interface CommonGraph {
    val foo: Foo
}

@BindingContainer
class A {
    @Provides
    fun foo(): Foo = Foo()
}

@ContributesTo(AppScope::class)
@BindingContainer(includes = [A::class])
class B

fun box(): String {
  return "OK"
}
