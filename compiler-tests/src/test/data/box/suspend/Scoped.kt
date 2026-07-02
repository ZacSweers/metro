// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ SuspendDoubleCheck lives in runtime-coroutines; putting the kotlinx-coroutines klib on the JS
// test classpath trips partial-linkage errors against dev test compilers, so this runs JVM-only.
// A scoped constructor-injected class with a suspend dep. The graph stores it in a
// SuspendProvider<T> field backed by an IR-only nested SuspendFactory, wrapped in
// SuspendDoubleCheck so both accessors share one instance.

abstract class AppScope private constructor()

@Inject
@SingleIn(AppScope::class)
class AccountCreator(val database: String)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  suspend fun otherCreator(): AccountCreator

  @Provides suspend fun provideDatabase(): String = "db"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph)
  return "OK"
}
