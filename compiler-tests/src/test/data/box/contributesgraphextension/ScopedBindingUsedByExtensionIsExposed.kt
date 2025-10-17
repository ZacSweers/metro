// Test case for scoped bindings used by contributed graph extensions
// https://github.com/ZacSweers/metro/issues/377
class HttpClient @Inject constructor()

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides
  @SingleIn(AppScope::class)
  fun provideHttpClient(): HttpClient = HttpClient()
}

@GraphExtension(Unit::class)
interface NetworkExtension {
  val service: NetworkService

  @Provides
  fun provideNetworkService(httpClient: HttpClient): NetworkService = NetworkService(httpClient)

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createNetworkExtension(): NetworkExtension
  }
}

class NetworkService(val httpClient: HttpClient)

fun box(): String {
  val appGraph = createGraph<AppGraph>()

  val extension = appGraph.createNetworkExtension()
  assertNotNull(extension.service)

  return "OK"
}