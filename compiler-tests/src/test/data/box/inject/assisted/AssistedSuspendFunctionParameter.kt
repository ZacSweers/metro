// A suspend function passed as an assisted argument is a caller-supplied value, not a suspend
// provider that requires Metro's suspend provider support to be enabled.
// https://github.com/ZacSweers/metro/issues/2617

@AssistedInject
class Foo(@Assisted private val suspendFun: suspend () -> List<String>) {
  fun function(): suspend () -> List<String> = suspendFun

  @AssistedFactory
  interface Factory {
    fun create(suspendFun: suspend () -> List<String>): Foo
  }
}

@DependencyGraph
interface AppGraph {
  fun fooFactory(): Foo.Factory
}

fun box(): String {
  val suspendFun: suspend () -> List<String> = { listOf("value") }
  val foo = createGraph<AppGraph>().fooFactory().create(suspendFun)
  assertSame(suspendFun, foo.function())
  return "OK"
}
