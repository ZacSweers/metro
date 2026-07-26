// MIN_COMPILER_VERSION: 2.4.20-Beta1
// ENABLE_PRIVATE_PROVIDER_PROPERTIES

// MODULE: lib
@Qualifier annotation class StringValue

@Qualifier annotation class IntValue

@Qualifier annotation class LongValue

interface Providers {
  @Provides @StringValue private val providedString: String get() = "Hello"

  @get:Provides @get:IntValue private val providedInt: Int get() = 42
}

abstract class FieldProviders {
  @Provides @LongValue private val providedLong: Long = 3L
}

// MODULE: main(lib)
@DependencyGraph
abstract class AppGraph : FieldProviders(), Providers {
  @StringValue abstract val string: String
  @IntValue abstract val int: Int
  @LongValue abstract val long: Long
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.string)
  assertEquals(42, graph.int)
  assertEquals(3L, graph.long)
  return "OK"
}
