@AssistedInject
class MessageReader(@Assisted private val readMessage: suspend () -> String) {
  suspend fun read(): String = readMessage()

  @AssistedFactory
  interface Factory {
    fun create(readMessage: suspend () -> String): MessageReader
  }
}

@DependencyGraph
interface ExampleGraph {
  val factory: MessageReader.Factory
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val reader = graph.factory.create { "message" }
  assertEquals("message", runBlocking { reader.read() })
  return "OK"
}
