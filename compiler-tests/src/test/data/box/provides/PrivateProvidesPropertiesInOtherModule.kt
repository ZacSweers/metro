// MIN_COMPILER_VERSION: 2.4.20-Beta1
// ENABLE_PRIVATE_PROVIDER_PROPERTIES

// MODULE: lib
@Qualifier annotation class StringValue

@Qualifier annotation class IntValue

interface Providers {
  @Provides @StringValue private val providedString: String get() = "Hello"

  @get:Provides @get:IntValue private val providedInt: Int get() = 42
}

// MODULE: main(lib)
@DependencyGraph
interface AppGraph : Providers {
  @StringValue val string: String
  @IntValue val int: Int
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.string)
  assertEquals(42, graph.int)
  return "OK"
}
