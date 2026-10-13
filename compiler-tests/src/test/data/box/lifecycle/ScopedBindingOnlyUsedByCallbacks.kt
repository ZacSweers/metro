// A scoped binding that only callbacks request is still cached, so init and close see the same
// instance.

@SingleIn(AppScope::class)
@Inject
class Connection {
  var isOpen = false
}

val connections = mutableListOf<Connection>()

@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  @OnGraphInit
  fun open(connection: Connection) {
    connection.isOpen = true
    connections += connection
  }

  @OnGraphClose
  fun closeConnection(connection: Connection) {
    connection.isOpen = false
    connections += connection
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(connections.single().isOpen)
  graph.close()
  assertEquals(2, connections.size)
  assertSame(connections[0], connections[1])
  assertFalse(connections[0].isOpen)
  return "OK"
}
