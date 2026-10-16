// ENABLE_DAGGER_INTEROP
// DIAGNOSTIC_LEVEL: MAP_KEY_WITHOUT_INTO_MAP:warning
import dagger.MapKey

@MapKey
annotation class Tag(val value: String)

interface Exposed {
  <!MAP_KEY_WITHOUT_INTO_MAP!>@Tag("a")<!>
  val value: String
}

@DependencyGraph
interface ExampleGraph : Exposed {
  @Provides fun provideValue(): String = "x"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return if (graph.value == "x") "OK" else "FAIL: ${graph.value}"
}
