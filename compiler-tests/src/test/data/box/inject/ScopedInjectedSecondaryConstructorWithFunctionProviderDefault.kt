// Regression test for https://github.com/ZacSweers/metro/issues/2200
// A scoped class with an @Inject secondary constructor whose function-typed parameter
// has a lambda default. With enableFunctionProviders on, the function type is treated
// as a provider intrinsic, and the default value needs to be wrapped as Provider<T>
// not Provider<() -> T>.
@SingleIn(AppScope::class)
class Foo(val someVariable: String, val getLanguage: () -> String) {
  @Inject
  constructor(
    getLanguage: () -> String = { "hello" }
  ) : this(someVariable = "hello", getLanguage = getLanguage)
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val foo: Foo
}

fun box(): String {
  val foo = createGraph<AppGraph>().foo
  assertEquals("hello", foo.someVariable)
  assertEquals("hello", foo.getLanguage())
  return "OK"
}
