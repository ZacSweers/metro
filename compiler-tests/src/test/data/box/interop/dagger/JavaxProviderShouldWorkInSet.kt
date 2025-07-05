// ENABLE_DAGGER_INTEROP
// Ref https://github.com/ZacSweers/metro/pull/666
import javax.inject.Provider

@DependencyGraph(AppScope::class)
interface AppGraph {
  val urlHandlers: Set<UrlHandler>
}

interface UrlHandler

@Inject
@ContributesIntoSet(AppScope::class)
class PostUrlHandler : UrlHandler {
  fun handle(url: String) {
    url.toString()
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.urlHandlers.single())
  return "OK"
}
