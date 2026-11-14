// Regression test for https://github.com/ZacSweers/metro/issues/3119.

interface DefaultProvider {
  suspend fun find(id: String): String?
}

@Inject
class Impl(
  val r: DefaultProvider = object : DefaultProvider {
    override suspend fun find(id: String): String? = null
  },
)

@DependencyGraph
interface AppGraph {
  val impl: Impl
}

fun box(): String {
  runBlocking {
    assertNull(Impl().r.find("id"))
    assertNull(createGraph<AppGraph>().impl.r.find("id"))
  }

  return "OK"
}
