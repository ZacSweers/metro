// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

private val messages = mutableListOf<String>()

@Inject
fun NoArguments() {
  messages += "Hello, world!"
}

@Inject
fun AssistedArgument(@Assisted message: String) {
  messages += message
}

@Inject
fun InjectedArgument(message: String) {
  messages += message
}

@Inject
fun MixedArguments(@Assisted int: Int, message: String): String = message + int

@Inject
fun InterleavedArguments(int: Int, @Assisted message: String, long: Long): String =
  message + int + long

@DependencyGraph
interface ArgumentGraph {
  val noArguments: NoArguments
  val assistedArgument: AssistedArgument
  val injectedArgument: InjectedArgument
  val mixedArguments: MixedArguments
  val interleavedArguments: InterleavedArguments

  @Provides val provideInt: Int get() = 2
  @Provides val provideLong: Long get() = 3

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides message: String): ArgumentGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<ArgumentGraph.Factory>().create("Hello, world!")
  graph.noArguments.invoke()
  graph.assistedArgument.invoke("Hello, world!")
  graph.injectedArgument.invoke()

  assertEquals(List(3) { "Hello, world!" }, messages)
  assertEquals("Hello, world!2", graph.mixedArguments.invoke(2))
  assertEquals("Hello, world!23", graph.interleavedArguments.invoke("Hello, world!"))
  return "OK"
}
