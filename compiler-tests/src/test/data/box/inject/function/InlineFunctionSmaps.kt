// ENABLE_TOP_LEVEL_FUNCTION_INJECTION
// FIX_EMPTY_SMAP_RANGES

// https://github.com/ZacSweers/metro/pull/3046
// The generated class lives in a synthetic file in both class generation modes.
// The assisted default inlines provider {} into that class.
@Inject
fun Message(value: String, @Assisted suffix: Provider<String> = provider { "!" }): String {
  return value + suffix()
}

@DependencyGraph
interface AppGraph {
  val message: Message

  @Provides fun value(): String = "provided"
}

fun box(): String {
  val message = createGraph<AppGraph>().message
  assertEquals("provided!", message())
  assertEquals("provided?", message(provider { "?" }))
  return "OK"
}
