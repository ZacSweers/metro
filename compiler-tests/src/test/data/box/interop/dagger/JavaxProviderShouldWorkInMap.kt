// ENABLE_DAGGER_INTEROP
// Ref https://github.com/ZacSweers/metro/pull/666
import javax.inject.Provider

@DependencyGraph(AppScope::class)
interface AppGraph {
  val urlHandlers: Map<String, Provider<UrlHandler>>
}

interface UrlHandler

@Inject
@ContributesIntoMap(AppScope::class)
@StringKey("post")
class PostUrlHandler : UrlHandler {
  fun handle(url: String) {
    url.toString()
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.urlHandlers["post"]?.get())
  return "OK"
}
