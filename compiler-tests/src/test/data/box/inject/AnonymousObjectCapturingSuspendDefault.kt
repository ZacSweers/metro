// Regression test for https://github.com/ZacSweers/metro/issues/3119.

interface DefaultProvider {
  suspend fun find(id: String): String?
}

@Inject
class Impl(
  val a: String = "A",
  val r: DefaultProvider = object : DefaultProvider {
    override suspend fun find(id: String): String? = a + id
  },
)

@DependencyGraph
interface AppGraph {
  val impl: Impl
}

fun box(): String {
  runBlocking {
    assertEquals("Aid", Impl().r.find("id"))
    assertEquals("Bid", Impl(a = "B").r.find("id"))
    assertEquals("Aid", createGraph<AppGraph>().impl.r.find("id"))
  }

  return "OK"
}
